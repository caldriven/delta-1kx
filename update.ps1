# Latest main -> phone (adb) + Windows app. Run from the repo root:  .\update.ps1
# Options: -NoPull (build what is checked out), -SkipAndroid, -SkipWindows
param([switch]$NoPull, [switch]$SkipAndroid, [switch]$SkipWindows)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$env:JAVA_HOME = 'C:\Users\Alex\dev\jdk21'
$adb = 'C:\Users\Alex\dev\android-sdk\platform-tools\adb.exe'
$winDir = Join-Path $env:USERPROFILE 'Delta1kx'   # the Windows app lands here, same place every time

if (-not $NoPull) {
    git fetch origin
    git checkout main
    git pull --ff-only origin main
}
Write-Host "Building $(git rev-parse --short HEAD)" -ForegroundColor Cyan

if (-not $SkipAndroid) {
    .\gradlew.bat :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    $apk = 'app\build\outputs\apk\release\app-release.apk'
    # adb writes its daemon start-up notes to stderr, which would trip ErrorActionPreference=Stop.
    $ErrorActionPreference = 'Continue'
    & $adb start-server *> $null
    $dev = & $adb devices 2>$null | Select-String "`tdevice$"
    if ($dev) {
        & $adb install -r --user 0 $apk
        if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
        Write-Host 'Phone updated.' -ForegroundColor Green
    } else {
        Write-Host "No phone connected (USB debugging on?). APK is at $apk" -ForegroundColor Yellow
    }
}

$ErrorActionPreference = 'Stop'
if (-not $SkipWindows) {
    .\gradlew.bat :desktop:createReleaseDistributable
    if ($LASTEXITCODE -ne 0) { throw 'Windows build failed' }
    Get-Process Delta1kx -ErrorAction SilentlyContinue | Stop-Process   # a running copy locks the files
    robocopy 'desktop\build\compose\binaries\main-release\app\Delta1kx' $winDir /MIR /NFL /NDL /NJH /NJS | Out-Null
    Write-Host "Windows app: $winDir\Delta1kx.exe" -ForegroundColor Green
}
