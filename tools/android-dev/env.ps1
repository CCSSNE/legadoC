$script:AndroidDevRoot = Split-Path -Parent $PSScriptRoot
$script:RepoRoot = Split-Path -Parent $script:AndroidDevRoot

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME = 'D:\AI\audio\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = 'D:\AI\audio\android-gradle-user-home'
$env:LEIDIAN_SERIAL = 'emulator-5554'
$env:LEIDIAN_HOME = 'F:\leidian\LDPlayer14'

function Assert-LDPlayerTarget {
    if ($env:LEIDIAN_SERIAL -ne 'emulator-5554') {
        throw "Refusing non-LDPlayer serial: $env:LEIDIAN_SERIAL"
    }
    $ldconsole = Join-Path $env:LEIDIAN_HOME 'ldconsole.exe'
    if (-not (Test-Path $ldconsole)) {
        throw "LDPlayer ldconsole.exe not found: $ldconsole"
    }
    if (-not (Get-Process -Name dnplayer -ErrorAction SilentlyContinue)) {
        throw 'LDPlayer is not running'
    }
    $instance = & $ldconsole list2 | Where-Object { $_ -like '0,*' } | Select-Object -First 1
    $fields = $instance -split ','
    if ($fields.Count -lt 5 -or $fields[4] -ne '1') {
        throw 'LDPlayer instance 0 is not reported as running'
    }
}

$pathEntries = @(
    "$env:JAVA_HOME\bin",
    "$env:ANDROID_HOME\cmdline-tools\latest\bin",
    "$env:ANDROID_HOME\platform-tools",
    "$env:ANDROID_HOME\build-tools\36.0.0",
    "$script:RepoRoot\.android-dev-venv\Scripts"
)
$env:Path = ($pathEntries + ($env:Path -split ';' | Where-Object { $_ })) -join ';'

Write-Output "Android dev environment loaded"
Write-Output "  repo:       $script:RepoRoot"
Write-Output "  emulator:   $env:LEIDIAN_SERIAL"
Write-Output "  sdk:        $env:ANDROID_HOME"
Write-Output "  python:     $script:RepoRoot\.android-dev-venv\Scripts\python.exe"
Write-Output "  adb policy: use only the explicit -s $env:LEIDIAN_SERIAL"
