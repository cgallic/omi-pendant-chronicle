# One-command firmware rebuild + OTA flash for the Omi pendant (nRF5340, app core),
# from Windows over BLE. Builds the firmware with the nRF Connect SDK, then flashes
# the signed app image via smpmgr (`pip install smpmgr` — it uses bleak, so it works
# on the same Bluetooth adapter as the collector). MCUboot does a test-swap, so a bad
# image auto-reverts on the next boot; only the app image is flashed (net core is
# untouched).
#
# Setup: copy to flash-firmware.ps1 and edit the paths / pendant address below.
# The pendant must be AWAKE (press the button) and in BLE range, phone Bluetooth OFF.
#
# Usage:  .\flash-firmware.ps1            (build + flash)
#         .\flash-firmware.ps1 -SkipBuild (flash the existing build only)
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'

$nrfutil = "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe"  # or wherever nrfutil is
$fw   = 'C:\path\to\omi\firmware'          # <-- Omi firmware dir (contains omi/ and boards/)
$ws   = 'C:\ncs\v2.9.0'                     # <-- nRF Connect SDK west workspace
$img  = Join-Path $fw 'build\omi\zephyr\zephyr.signed.bin'
$addr = 'AA:BB:CC:DD:EE:FF'                 # <-- pendant BLE address (from `pendant-collector scan`)
$smp  = 'smpmgr'                            # on PATH, or full path to the venv smpmgr.exe

if (-not $SkipBuild) {
    Write-Host "== Building firmware (pristine) ==" -ForegroundColor Cyan
    Push-Location $ws
    try {
        & $nrfutil toolchain-manager launch --ncs-version v2.9.0 -- `
            west build -p always -b omi/nrf5340/cpuapp -d "$fw/build" "$fw/omi" `
            --sysbuild -- -DBOARD_ROOT="$fw"
    } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "firmware build failed (rc=$LASTEXITCODE)" }
}
if (-not (Test-Path $img)) { throw "signed app image not found: $img" }

Write-Host "== Flashing app image over BLE (MCUboot test-swap, reversible) ==" -ForegroundColor Cyan
# smpmgr prints a Unicode spinner that crashes the Windows cp1252 console; force UTF-8.
$env:PYTHONUTF8 = '1'; $env:PYTHONIOENCODING = 'utf-8'
# Tip: run `pendant-collector scan` first so Windows/WinRT has the pendant cached,
# otherwise smpmgr's connect-by-address can time out.
& $smp --ble $addr --timeout 30 upgrade $img
if ($LASTEXITCODE -ne 0) { throw "smpmgr upgrade failed (rc=$LASTEXITCODE)" }

Write-Host "== Done. Pendant reboots on the new firmware. ==" -ForegroundColor Green
