# pendant-android-app

Custom Kotlin Android companion app for the Omi pendant based Chronicle second brain.

The app pairs with the pendant directly over BLE, bypasses the Omi cloud, and renders the existing agent backend as a polished native Android experience. The current native shell is not a WebView. It uses programmatic Android views plus Material Components.

**Build host:** an Android-toolchain box reachable over your LAN/tailnet (referred to below as `agent`).
**Target device:** any Android phone reachable over ADB (USB or `adb connect <ip>:<port>`).

## Current Product Shape

Chronicle is a wearable companion cockpit with a visible evidence spine:

- Pendant captures audio continuously or during explicit live mode.
- Phone runs low-power push-to-sync mode by default.
- User can explicitly Sync Now, Start Live, and Stop Live.
- App surfaces capture state, translation mode, memories, TODOs, Scout approvals, knowledge map, health context, and assistant chat.
- Sync, Listen, and Translate are promoted into a persistent Quick Controls band.
- Processing is the truth console for raw audio, local files, upload state, decoded WAVs, transcript chunks, memory status, and reconciliation.
- Debug/service controls still live in the Settings bottom sheet, not the primary UI.

Do not overcorrect into a pipeline-only app. The pipeline is the evidence spine, not the whole product. The product is a wearable companion for capture, translation, actions, memory, knowledge, and tools. The UI should make the pipeline trustworthy while keeping the user focused on what they can do next.

## Native App Architecture

```
MainActivity
  -> native Material shell
  -> bottom nav: Today, Capture, Translate, To-dos, Scout, Memories, Map, Health, Ask, Process
  -> prominent quick controls: Sync, Listen, Translate
  -> settings bottom sheet for Sync/Live/Debug/Stop Service

ChronicleApi
  -> reads agent:8772 /pendant/api/*
  -> POSTs action toggles, approval decisions, listening state, chat
  -> reads/writes translation state through agent:8774/state
  -> tolerates per-endpoint failures so a slow status call does not blank the feed

PendantForegroundService
  -> single owner of BLE, wake locks, audio-out, context heartbeats, Health Connect
  -> low-power mode keeps BLE/audio WebSocket idle
  -> live mode starts BLE stream plus audio-out WebSocket

OmiBleClient
  -> scans, connects, drains storage, streams live notifications

AgentUploader
  -> posts raw frames to agent:8773/raw or /raw-batch

AudioStore
  -> 3-day local ring buffer of raw Omi-framed Opus packets
```

## Important Files

```
app/src/main/kotlin/com/connor/pendant/
├── MainActivity.kt                  # Native Chronicle app shell
├── DebugActivity.kt                 # Original debug/status surface
├── PendantBridge.kt                 # Bridge used by legacy/web surfaces
├── net/
│   ├── ChronicleApi.kt              # Dashboard/API client for native UI
│   └── AgentUploader.kt             # Raw frame uploader to agent:8773
├── service/
│   └── PendantForegroundService.kt  # BLE/background/live/audio-out owner
├── audio/                           # Audio-out WebSocket client + player
├── ble/                             # Omi GATT UUIDs/client
├── context/                         # GPS, steps, foreground app heartbeat
├── health/                          # Health Connect reads/backfill
├── store/                           # Room-backed audio segment store
└── work/                            # Retention worker
```

## Backend Endpoints Used By Native UI

The native shell binds directly to the live backend schema.

| Screen | Endpoint |
|---|---|
| Header/status | `GET /pendant/api/v3/status` |
| Memories | `GET /pendant/api/v3/memories?days_back=2&limit=50` |
| Memory detail | `GET /pendant/api/v3/conversation/{id}` |
| Actions | `GET /pendant/api/v2/action-items?days_back=14&completed=false` |
| Toggle action | `POST /pendant/api/v2/action-items/{conv_id}/{index}/toggle` |
| Approvals | `GET /pendant/api/v3/approvals` |
| Approve/reject | `POST /pendant/api/v3/approvals/{job_id}/approve` or `/reject` |
| Live state | `GET /pendant/api/v3/live` |
| Listening state | `GET /pendant/api/v3/listening` |
| Start/stop listening | `POST /pendant/api/v3/listening` |
| Health | `GET /pendant/api/v3/health` |
| Assistant chat | `POST /pendant/api/v3/chat` |
| Knowledge map | `GET /pendant/api/v3/graph?days_back=14` |
| Chronicle insight | `GET /pendant/api/v3/insights` |
| Translation state | `GET /state` on `agent:8774` |
| Translation mode | `POST /state` on `agent:8774` |

Schema notes:

- Memories are returned under top-level `memories`.
- Memory title/overview/category are nested under `memory.structured`.
- Action items are returned under top-level `items`, not `actions`.
- Action item completion uses `done`.
- Approval rows may use `id`; pending approval responses may refer to that id as `job_id` in product language.

## Configuration

`local.properties` is gitignored and read at build time. Copy `local.properties.example`
to `local.properties` and fill in your own values:

```properties
sdk.dir=/path/to/android-sdk
agent.url=http://YOUR_AGENT_HOST:8773/raw
pendant.secret=your-32-hex-shared-secret
audio.out.url=ws://YOUR_AGENT_HOST:8774/ws
```

`agent.url` points at raw ingest on port `8773`. The app derives the dashboard/API base from the same host on port `8772`. `pendant.secret` must match the `PENDANT_SECRET` the backend raw sink validates against.

## Build And Install

The Android toolchain lives on a build host (referred to here as `agent`). One workflow: sync the tree to the build host, build there, pull the APK back, install over ADB.

```powershell
# Sync + build on the agent box (see scripts/sync-and-build.ps1 for a turnkey version)
scp -r android-app root@agent:/opt/pendant-android-app/
ssh root@agent "cd /opt/pendant-android-app && gradle :app:assembleDebug"
scp root@agent:/opt/pendant-android-app/app/build/outputs/apk/debug/app-debug.apk chronicle-debug.apk

# Install on a phone reachable over ADB (USB, or `adb connect <ip>:<port>` first)
adb install -r chronicle-debug.apk
adb shell am start -n com.connor.pendant/.MainActivity
```

If the repo gains a Gradle wrapper, prefer `./gradlew :app:assembleDebug` on the build host.

## Verification Checklist

After install:

1. Launch `com.connor.pendant/.MainActivity`.
2. Confirm Today starts with `What's happening now`, then Quick Controls, `What needs me`, latest memory, and the compact evidence spine.
3. Confirm Process contains the full truth console: pipeline stages, backend audio, pendant files, and phone-local audio.
4. Confirm Translate opens the Auto / On / Off bottom sheet and shows translation mode, target/detected language, audio-out state, and snippets.
5. Confirm the Memories tab treats memories as finished artifacts with source/provenance and retrieval affordances.
6. Confirm To-dos reads top-level `items`, displays checkboxes, and only shows user-owned follow-ups.
7. Confirm Scout contains agent tasks plus approvals: if Scout does the work, it waits here for approval; if the user does it, it belongs in To-dos.
8. Confirm Map, Health, and Ask are first-class native screens, matching the v3 web tabs and chat surface.
9. Open Process and confirm the full web/debug surfaces are one tap away: Web Home, Live, Status, Chat, Raw, and Audio.
10. Open Settings and confirm Reload, Debug, Stop Service, web shortcuts, and secondary service controls are tucked away.
11. Run logcat and check for app crashes:

```powershell
adb logcat -d -t 800 |
  Select-String -Pattern 'AndroidRuntime|FATAL EXCEPTION|MainActivity|ChronicleApi|PendantFgService'
```

Latest verified build:

- Built on the agent box with `gradle :app:assembleDebug`.
- Installed to a Pixel test device over ADB.
- No app crash or fatal exception in logcat during Memories, Actions, Live, or Settings navigation.

## Service Modes

Low-power mode:

- Service can remain foreground.
- BLE stays disconnected/idle unless user requests Sync Now.
- Audio-out WebSocket is not kept alive.
- Context heartbeat continues on its interval.

Explicit live mode:

- `PendantForegroundService.startLive()` starts BLE live stream.
- Audio-out connects to `AUDIO_OUT_WS_URL`.
- A wake lock is held until Stop Live.
- Native UI also POSTs listening state to `/pendant/api/v3/listening`.

## Known Rough Edges

- Action toggles and approval decisions mutate live backend state; test carefully.
- Health tab currently renders whatever `/pendant/api/v3/health` returns. Today that can be only steps.
- The UI is native but still hand-built programmatic views, not Compose.
- `GET /pendant/api/v3/status` can be slower than the other endpoints. `ChronicleApi.snapshot()` isolates endpoint failures so Memories/Actions still render.
- Build/sync is still manual `scp` to the agent build host.

## Legacy Milestone Notes

M1 proved direct BLE capture:

1. At least 500 frames received in a 30-second wear test.
2. No gaps over 2 seconds in frame timestamps.
3. No `Service destroying` lines for `PendantForegroundService`.
4. Agent-side raw frame folder count matched phone counter.
5. Screen on/off did not break frame rate.

M2.5 added local audio storage. See `STORE_M2.5.md`.
