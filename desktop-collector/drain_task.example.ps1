# Opportunistic pendant drain — run on a schedule (e.g. Task Scheduler, every 30 min).
# Scans briefly for the pendant; if it's awake + in range (and not held by the phone
# or another drain), drains its stored audio to your backend, then exits. If the
# pendant isn't reachable, exits quietly. Safe to fire on a timer.
#
# Setup: copy to drain_task.ps1, set the paths below, and provide the secret + agent
# URL via the PENDANT_SECRET / PENDANT_AGENT_URL environment variables (do NOT hardcode
# the secret in a file you commit).
#
# IMPORTANT: don't launch the collector with `& $exe ... *>> $log` under Task
# Scheduler. A hidden-window (-WindowStyle Hidden) native child process has no
# console handle to write to, so `*>>` silently drops ALL of its stdout/stderr —
# you'll only ever see "drain run start" / "drain run end rc=1" with zero
# diagnostic content, no matter what actually failed. Launch via
# System.Diagnostics.Process with explicit RedirectStandardOutput/Error (real
# pipes) instead, as below. Also: Start-Process -PassThru's returned object can
# fail to expose .ExitCode reliably when output is redirected to files on
# Windows PowerShell 5.1 — the raw Process class doesn't have that problem.
$ErrorActionPreference = 'SilentlyContinue'

$dir     = 'C:\path\to\desktop-collector'          # <-- edit
$exe     = Join-Path $dir '.venv\Scripts\pendant-collector.exe'
$logdir  = Join-Path $dir 'logs'
$log     = Join-Path $logdir ("drain-{0}.log" -f (Get-Date -Format 'yyyy-MM-dd'))
# Hard wall-clock backstop only — NOT a routine limiter. A real drain can
# legitimately need up to (storage-ready-timeout + stall-timeout) seconds for
# a single large file; set this comfortably above that sum so it only fires on
# a genuine hang, not to cut off real in-progress progress.
$maxSecs = 900
New-Item -ItemType Directory -Force $logdir | Out-Null

function Write-Log([string]$line) {
    "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $line | Add-Content $log
}

# Single-instance guard: the pendant is a single-connection BLE peripheral, so skip
# if a drain is already running.
$busy = Get-CimInstance Win32_Process -Filter "Name='pendant-collector.exe' OR Name='python.exe'" |
        Where-Object { $_.CommandLine -like '*pendant-collector*sync*' -or $_.CommandLine -like '*pendant_collector*' }
if ($busy) { Write-Log "skip: drain already running"; exit 0 }

$env:PYTHONUNBUFFERED = '1'
$env:PYTHONUTF8       = '1'
$env:PYTHONIOENCODING = 'utf-8'
# PENDANT_SECRET and PENDANT_AGENT_URL are read from the environment by the CLI.
# Set PENDANT_ADDRESS too if you want to pin a specific pendant.

# Per-run temp file names (not fixed names) so an overlapping/manual invocation
# can never collide with the scheduled one on the same file.
$stdoutFile = Join-Path $logdir ("drain-run-{0}.stdout.tmp" -f $PID)
$stderrFile = Join-Path $logdir ("drain-run-{0}.stderr.tmp" -f $PID)
Remove-Item $stdoutFile, $stderrFile -ErrorAction SilentlyContinue

$argList = @(
    'sync',
    '--scan-timeout', '25',
    '--stall-timeout', '180',
    '--storage-ready-timeout', '600'
)

Write-Log "drain run start"
$rc = $null
try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    # ArgumentList isn't reliably populated on every PS 5.1 / .NET Framework
    # combo — a plain joined string via .Arguments is simplest and safe as
    # long as no argument contains spaces.
    $psi.Arguments = ($argList -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $proc.Start() | Out-Null
    $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
    $stderrTask = $proc.StandardError.ReadToEndAsync()

    if (-not $proc.WaitForExit($maxSecs * 1000)) {
        Write-Log ("TIMEOUT after {0}s — killing pid={1} (and any child)" -f $maxSecs, $proc.Id)
        Get-CimInstance Win32_Process -Filter "Name='pendant-collector.exe' OR Name='python.exe'" |
            Where-Object { $_.CommandLine -like '*pendant-collector*sync*' -or $_.CommandLine -like '*pendant_collector*' } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        $rc = 'timeout'
    } else {
        $rc = $proc.ExitCode
    }
    [System.IO.File]::WriteAllText($stdoutFile, $stdoutTask.Result)
    [System.IO.File]::WriteAllText($stderrFile, $stderrTask.Result)
} catch {
    Write-Log ("EXCEPTION launching collector: {0}" -f $_.Exception.Message)
    $rc = 'launch-error'
}

foreach ($f in @(@{path=$stdoutFile; tag='OUT'}, @{path=$stderrFile; tag='ERR'})) {
    if (Test-Path $f.path) {
        Get-Content $f.path | Where-Object { $_ -ne '' } | ForEach-Object { Write-Log ("[{0}] {1}" -f $f.tag, $_) }
        Remove-Item $f.path -ErrorAction SilentlyContinue
    }
}

Write-Log ("drain run end rc={0}" -f $rc)
