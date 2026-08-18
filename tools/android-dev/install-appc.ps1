param([string]$Apk = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$adb = Join-Path $env:LEIDIAN_HOME 'adb.exe'
$aapt = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt.exe'
$apksigner = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\apksigner.bat'
if ([string]::IsNullOrWhiteSpace($Apk)) {
    $Apk = Get-ChildItem (Join-Path $script:RepoRoot 'app\build\outputs\apk\app\c') -Filter '*.apk' -File |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Apk -or -not (Test-Path $Apk)) { throw 'No appC APK found' }
$apkFullPath = (Resolve-Path $Apk).Path
if ($apkFullPath -notmatch '\\app\\build\\outputs\\apk\\app\\c\\') { throw "Refusing APK outside appC output: $apkFullPath" }

$badging = & $aapt dump badging $apkFullPath
$packageMatch = [regex]::Match(($badging -join "`n"), "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
if (-not $packageMatch.Success) { throw 'Unable to read APK badging' }
if ($packageMatch.Groups[1].Value -ne 'io.legado.app.c') { throw "Unexpected package: $($packageMatch.Groups[1].Value)" }
if (($badging -join "`n") -notmatch "native-code: 'arm64-v8a'") { throw 'APK is not arm64-v8a appC output' }
& $apksigner verify --print-certs $apkFullPath | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
$newCode = [int64]$packageMatch.Groups[2].Value

Assert-LDPlayerTarget
& $adb -s $env:LEIDIAN_SERIAL connect $env:LEIDIAN_SERIAL | Out-Null
if ((& $adb -s $env:LEIDIAN_SERIAL get-state).Trim() -ne 'device') { throw 'LDPlayer transport is not ready' }
$installed = & $adb -s $env:LEIDIAN_SERIAL shell dumpsys package io.legado.app.c
$installedMatch = [regex]::Match(($installed -join "`n"), 'versionCode=(\d+)')
if ($installedMatch.Success -and $newCode -lt [int64]$installedMatch.Groups[1].Value) {
    throw "Refusing downgrade: APK versionCode=$newCode, installed=$($installedMatch.Groups[1].Value)"
}

& $adb -s $env:LEIDIAN_SERIAL install -r $apkFullPath
if ($LASTEXITCODE -ne 0) { throw "APK install failed with exit code $LASTEXITCODE" }
$after = & $adb -s $env:LEIDIAN_SERIAL shell dumpsys package io.legado.app.c
$afterMatch = [regex]::Match(($after -join "`n"), 'versionCode=(\d+).*?versionName=([^\s]+)', [Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $afterMatch.Success) { throw 'Unable to verify installed APK version' }
Write-Output "Installed io.legado.app.c versionCode=$($afterMatch.Groups[1].Value) versionName=$($afterMatch.Groups[2].Value) on $env:LEIDIAN_SERIAL"
