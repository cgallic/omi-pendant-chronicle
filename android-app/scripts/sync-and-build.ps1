# sync-and-build.ps1 — Windows-side orchestrator for the agent-box bootstrap.
#
# Rsyncs the tree to /opt/pendant-android-app on the agent box, kicks off the
# build script, and pulls the resulting APK back to _builds/.
#
# Prereqs:
#   - SSH key access: `ssh root@agent` works without a prompt
#   - rsync available in PATH (Git Bash, MSYS2, or scoop install rsync)
#   - PENDANT_SECRET already saved at /srv/ai/pendant/.env.pendant-secret on agent
#       (one-time: ssh root@agent 'echo PENDANT_SECRET=$(openssl rand -hex 16) > /srv/ai/pendant/.env.pendant-secret')
#
# Usage (from this directory):
#   .\scripts\sync-and-build.ps1
#
# Or from anywhere:
#   pwsh .\scripts\sync-and-build.ps1   (run from the android-app/ directory)

$ErrorActionPreference = "Stop"

# Adjust these to your environment. Run this script from the android-app/ directory.
$AgentTree = "root@agent:/opt/pendant-android-app/"
$ApkOut    = "$PSScriptRoot\..\chronicle-debug.apk"

Write-Host "==> Packing files using tar" -ForegroundColor Cyan
if (Test-Path pendant.tar) { Remove-Item pendant.tar }
tar --exclude="app/build" --exclude=".gradle" --exclude=".kotlin" --exclude="local.properties" --exclude="*.apk" -cf pendant.tar .
if ($LASTEXITCODE -ne 0) { throw "tar failed" }

Write-Host "==> Copying package to agent" -ForegroundColor Cyan
scp pendant.tar root@agent:/tmp/pendant.tar
if ($LASTEXITCODE -ne 0) { throw "scp failed" }

Write-Host "==> Extracting package on agent" -ForegroundColor Cyan
ssh root@agent "mkdir -p /opt/pendant-android-app && tar -xf /tmp/pendant.tar -C /opt/pendant-android-app && rm /tmp/pendant.tar"
if ($LASTEXITCODE -ne 0) { throw "extraction failed" }

if (Test-Path pendant.tar) { Remove-Item pendant.tar }

Write-Host "==> Build on agent" -ForegroundColor Cyan
ssh root@agent 'source /srv/ai/pendant/.env.pendant-secret && export PENDANT_SECRET && bash /opt/pendant-android-app/scripts/bootstrap-on-agent.sh'
if ($LASTEXITCODE -ne 0) { throw "remote build failed" }

Write-Host "==> Pull APK back to $ApkOut" -ForegroundColor Cyan
scp root@agent:/opt/pendant-android-app/app/build/outputs/apk/debug/app-debug.apk $ApkOut
if ($LASTEXITCODE -ne 0) { throw "scp failed" }

$sha = (Get-FileHash $ApkOut -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $ApkOut).Length / 1MB
Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host " APK at $ApkOut" -ForegroundColor Green
Write-Host "   size:   $([math]::Round($size, 1)) MB" -ForegroundColor Green
Write-Host "   sha256: $sha" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "Install on Pixel (USB-C connected to this Windows machine):"
Write-Host "  adb install -r $ApkOut"
