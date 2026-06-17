# Pendant Android — M2.5 Local Audio Store

**Added 2026-05-13** alongside the v3 web app rebuild.

## What this is

The Kotlin app now keeps a 3-day hot ring buffer of raw Opus frames on the phone, alongside the existing real-time POST to `agent:8773/raw`. M1 capture flow is unchanged (BLE → agent every frame); the new code writes the same frames to disk in 60-second segments.

```
BLE frame  ─┬──►  AgentUploader.postFrame()   (M1, unchanged)
            └──►  AudioStore.acceptFrame()    (M2.5, new — buffers + indexes)
```

## File layout on phone

```
/data/data/com.connor.pendant/files/audio/
  2026-05-11/
    143015.opusraw
    143115.opusraw
    ...
  2026-05-12/
    ...
  2026-05-13/
    091245.opusraw   ← current writing
```

Each `.opusraw` = ~60s of raw Omi-framed Opus frames (3-byte header + payload, no Ogg wrap). Decoder logic on the agent side already handles that wire shape.

## DB

Room database `pendant.db` (single table `audio_segments`):

```
id           INTEGER PK
path         TEXT       — absolute path to the .opusraw
date_key     TEXT       — "YYYY-MM-DD"
started_at   INTEGER    — epoch ms when first frame landed
ended_at     INTEGER    — epoch ms when segment finalized (60s after start)
frame_count  INTEGER
byte_count   INTEGER
posted_at    INTEGER?   — set when frames are believed-delivered to agent
deleted_at   INTEGER?   — set when retention sweep deletes the file
```

## Retention

`RetentionWorker` runs every 6 hours (WorkManager periodic). Walks rows where `started_at < now - 3 days AND deleted_at IS NULL`, deletes the file, sets `deleted_at`.

To change the retention window, edit `AudioStore`'s `retentionDays = 3` constructor default.

## Status surface

MainActivity now shows 5 new lines:

```
Stored:    14h 22m  /  98.6MB
Newest:    8s ago
Oldest:    1d 4h ago
Sync queue: 0 segments
Current:   42 frames / 18.3KB / 12s
```

These poll every 2s via `lifecycleScope` against the Room DAO.

## Build

Two new deps + KSP plugin. The version catalog (`gradle/libs.versions.toml`) was updated to add:

- `ksp = "2.0.21-1.0.27"` (must match Kotlin 2.0.21; bump in lockstep if Kotlin upgrades)
- `room = "2.6.1"`
- `work = "2.10.0"`

If `./gradlew :app:assembleDebug` errors with **"KSP version mismatch"**, look up the right pair at https://github.com/google/ksp/releases — pick the `<kotlinVersion>-<kspMinor>` tag matching your `kotlin` version line.

### From the repo root

```bash
cd _forks/pendant-android-app
# Connect phone via USB or Wi-Fi ADB
adb devices             # should list your Pixel
./gradlew :app:installDebug
adb shell am start -n com.connor.pendant/.MainActivity
adb logcat | grep -E "PendantFgService|AudioStore|RetentionWorker"
```

## Smoke checks

1. **M1 still works** — open app, tap Start, watch the "Posted:" counter climb on screen. Agent box should still receive frames at `:8773/raw`.
2. **AudioStore is writing** — within ~60s of Start, look for `AudioStore: new segment: /data/.../audio/2026-05-13/HHMMSS.opusraw` in logcat. Verify the file exists:
   ```bash
   adb shell run-as com.connor.pendant ls files/audio/$(date -u +%Y-%m-%d)/
   ```
3. **DB row inserted** — after a segment rolls (>60s of capture):
   ```bash
   adb shell run-as com.connor.pendant sqlite3 databases/pendant.db \
       "SELECT id, datetime(started_at/1000,'unixepoch'), frame_count, byte_count FROM audio_segments;"
   ```
4. **Status UI populates** — the "Stored:" line should show >0 within 90s after Start.
5. **Retention worker scheduled** — should appear in:
   ```bash
   adb shell dumpsys jobscheduler | grep -A2 pendant
   ```

## Known limitations (deferred)

- **Phase A is optimistic about uploads.** `posted_at` is set immediately on segment finalize. There's no per-segment ack from the agent; if the agent missed frames mid-segment, the sync queue won't reflect it. Phase B will add: agent endpoint that replays a segment file, and `posted_at` gets set only on 2xx ack.
- **No replay-from-phone yet.** The v3 web app can't pull audio from the phone. Phase C will add a tiny embedded Ktor server on the phone exposing `/audio/<id>` over the Tailscale interface.
- **`.opusraw` is not a player-friendly format.** Has 3-byte Omi headers per packet, no Ogg framing. Phase D adds an offline converter that emits proper `.opus` (or `.wav` via decoded PCM) for sharing.

## Rollback

The new code is additive. If you want to disable just the audio store and keep M1 behavior:

In `PendantForegroundService.kt`, comment out:
- `store = AudioStore(this)` in `onCreate`
- `store.acceptFrame(frame)` in the frames `onEach`
- `RetentionWorker.schedule(this)` after the onEach block
- `if (::store.isInitialized) store.close()` in `onDestroy`

The build will still link (Room types exist but aren't instantiated). No DB or files get created.

To remove entirely: revert the changes in `libs.versions.toml` + `app/build.gradle.kts` + delete `app/src/main/kotlin/com/connor/pendant/store/` and `.../work/`.
