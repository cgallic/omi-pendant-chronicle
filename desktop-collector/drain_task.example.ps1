# Opportunistic pendant drain — run on a schedule (e.g. Task Scheduler, every 30 min).
# Scans briefly for the pendant; if it's awake + in range (and not held by the phone
# or another drain), drains its stored audio to your backend, then exits. If the
# pendant isn't reachable, exits quietly. Safe to fire on a timer.
#
# Setup: copy to drain_task.ps1, set the paths below, and provide the secret + agent
# URL via the PENDANT_SECRET / PENDANT_AGENT_URL environment variables (do NOT hardcode
# the secret in a file you commit).
$ErrorActionPreference = 'SilentlyContinue'

$dir    = 'C:\path\to\desktop-collector'          # <-- edit
$exe    = Join-Path $dir '.venv\Scripts\pendant-collector.exe'
$logdir = Join-Path $dir 'logs'
$log    = Join-Path $logdir ("drain-{0}.log" -f (Get-Date -Format 'yyyy-MM-dd'))
New-Item -ItemType Directory -Force $logdir | Out-Null

# Single-instance guard: the pendant is a single-connection BLE peripheral, so skip
# if a drain is already running.
$busy = Get-CimInstance Win32_Process -Filter "Name='pendant-collector.exe' OR Name='python.exe'" |
        Where-Object { $_.CommandLine -like '*pendant-collector*sync*' -or $_.CommandLine -like '*pendant_collector*' }
if ($busy) { "[{0}] skip: drain already running" -f (Get-Date -Format 'HH:mm:ss') | Add-Content $log; exit 0 }

$env:PYTHONUNBUFFERED = '1'
$env:PYTHONUTF8       = '1'
$env:PYTHONIOENCODING = 'utf-8'
# PENDANT_SECRET and PENDANT_AGENT_URL are read from the environment by the CLI.
# Set PENDANT_ADDRESS too if you want to pin a specific pendant.

"[{0}] drain run start" -f (Get-Date -Format 'HH:mm:ss') | Add-Content $log
& $exe sync `
    --scan-timeout 25 `
    --stall-timeout 180 `
    --storage-ready-timeout 600 *>> $log
"[{0}] drain run end rc={1}" -f (Get-Date -Format 'HH:mm:ss'), $LASTEXITCODE | Add-Content $log
