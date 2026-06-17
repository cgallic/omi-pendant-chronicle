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
pendant-collector watch --interval 120 --max-files-per-pass 2
```

## Notes

- Do not run this at the same time as the Android app until the server-side
  collector lease endpoint exists. Two collectors can otherwise upload the same
  pendant file before either deletes it.
- BLE storage reads are still serial. This improves coverage and computer-side
  availability; it does not make the pendant radio itself parallel.
- A stronger Bluetooth adapter and closer physical placement can improve speed.
- `watch` mode is intended for always-nearby desktops. It wakes up on an
  interval, scans/connects, drains a bounded number of files, then sleeps again.
