# Pendant Desktop Collector

Cross-platform desktop BLE collector for the Omi pendant. It mirrors the working
Android storage sync path:

1. Connect to an advertising `Omi` / `Friend` pendant.
2. Subscribe to the storage notification characteristic.
3. List offline files.
4. Read one file at a time.
5. Rebuild Omi-framed Opus frames.
6. Upload frames to the existing `/raw-batch` sink.
7. Delete the pendant file only after upload succeeds.

This is intentionally a CLI first. It is the safest building block for running on
nearby computers as a scheduled collector.

## Install

```powershell
cd desktop-collector
python -m venv .venv
.\.venv\Scripts\python -m pip install -e .
```

## Run

The agent URL and shared secret come from environment variables (recommended, so
they never land in your shell history) or explicit flags. The secret must match
the `PENDANT_SECRET` the raw sink validates against.

```powershell
$env:PENDANT_AGENT_URL = "http://YOUR_AGENT_HOST:8773/raw"
$env:PENDANT_SECRET    = "your-32-hex-shared-secret"
.\.venv\Scripts\pendant-collector sync
```

Or pass them explicitly:

```powershell
.\.venv\Scripts\pendant-collector sync --agent-url http://YOUR_AGENT_HOST:8773/raw --secret your-32-hex-shared-secret
```

Useful options:

```powershell
pendant-collector scan
pendant-collector sync --address AA:BB:CC:DD:EE:FF   # pin a specific pendant BLE address
pendant-collector sync --once
pendant-collector sync --max-files 3
pendant-collector sync --dry-run-delete
pendant-collector sync --storage-ready-timeout 600   # wait out a large-backlog boot scan
pendant-collector watch --interval 120 --max-files-per-pass 2
```

## Reliability

The collector is resilient to the pendant's storage subsystem being transiently
busy:

- It **lists once per batch** and drains oldest-first (reading/deleting index 0),
  instead of re-listing before every file.
- `LIST`, `READ`, and `DELETE` all retry the firmware's `STORAGE_NOT_READY(9)`
  status (the SD/LittleFS worker is momentarily busy) with backoff, bounded by
  `--storage-ready-timeout` (default 90 s).
- `READ` **resumes from the current byte offset** if it stalls or the worker goes
  busy mid-file, instead of re-reading the whole file from scratch (the firmware
  seeks to the requested offset).
- An empty pendant ends the sync cleanly (a lone `0` from `LIST` is the firmware's
  empty-storage response), so a no-`--max-files` run terminates when drained.
- The pendant only advertises **when it is awake** and only accepts **one** BLE
  central at a time — so keep the phone's Bluetooth off (or the app closed) while
  draining from the desktop.

### Drain coordination (lease)

So the desktop and phone don't drain at the same time (and re-upload each other's
files), the collector holds a **lease** on the backend before draining, renews it
between files, and releases it when done. If another client holds the lease, this
pass is skipped. It's best-effort: if the backend has no lease endpoint or is
unreachable, the collector proceeds anyway (`--no-lease` disables it explicitly;
`--lease-holder` / `PENDANT_LEASE_HOLDER` sets the id, default `desktop-<hostname>`).

The backend must expose three secret-gated endpoints (a single global TTL lease):

```
POST /lease/acquire?holder=<id>&ttl=<seconds>   -> {"granted": bool, "holder", "expires_in"}
POST /lease/release?holder=<id>                 -> {"released": true}
GET  /lease                                     -> {"held": bool, "holder", "expires_in"}
```

For the phone and desktop to actually coordinate, the Android app must honor the
same lease.

### Large backlogs & firmware

On a nearly-full SD card, LittleFS's block allocator repeatedly re-scans the
whole filesystem (minutes over SPI), which stalls reads and can drop the BLE
link. Two things make large-backlog draining reliable:

1. **Collector side:** raise `--storage-ready-timeout` (e.g. `600`) so the first
   scan after connect is waited out instead of failing.
2. **Firmware side (recommended if you carry a big backlog):** in the Omi
   firmware, enlarge the LittleFS lookahead to cover the whole card and run the
   allocator pre-warm (`lfs_fs_gc`) once at boot — so the expensive scan happens
   offline before any BLE central connects, and the allocator never re-scans
   mid-drain. Keeping the card drained (well under ~50% full) avoids the pathology
   entirely. You can rebuild and flash this firmware **over BLE** (no cable) with
   `smpmgr` — see `flash-firmware.example.ps1`.

Throughput over BLE is ~60 KB/s on Windows (WinRT + Python) and ~120–175 KB/s
from a Linux/BlueZ host; the pendant firmware is already tuned (2M PHY, DLE 251).

## Scheduled / throughout-the-day sync

To keep the pendant drained automatically, run the collector on a timer whenever
it is nearby and awake. `drain_task.example.ps1` is a Windows wrapper (single-run,
skips if a drain is already in flight, logs per day); register it with Task
Scheduler, e.g. every 30 minutes:

```powershell
schtasks /Create /TN PendantDrain /SC MINUTE /MO 30 /IT /F `
  /TR 'powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "C:\path\to\drain_task.ps1"'
```

On Linux, a cron entry or systemd timer calling `pendant-collector sync` achieves
the same thing.

## Notes

- Desktop and phone draining is coordinated by the backend **lease** (see *Drain
  coordination* above) so they don't re-upload each other's files — provided both
  clients honor it. Until the Android app does, still prefer one drainer at a time.
- BLE storage reads are still serial. This improves coverage and computer-side
  availability; it does not make the pendant radio itself parallel.
- A stronger Bluetooth adapter and closer physical placement can improve speed.
- `watch` mode is intended for always-nearby desktops. It wakes up on an
  interval, scans/connects, drains a bounded number of files, then sleeps again.
