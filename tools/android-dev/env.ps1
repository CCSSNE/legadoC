$script:AndroidDevRoot = Split-Path -Parent $PSScriptRoot
$script:RepoRoot = Split-Path -Parent $script:AndroidDevRoot
$targetConfigPath = Join-Path $PSScriptRoot 'target.json'
if (-not (Test-Path -LiteralPath $targetConfigPath)) {
    throw "Android-dev target config is missing: $targetConfigPath"
}
try {
    $script:LDPlayerTarget = Get-Content -LiteralPath $targetConfigPath -Raw | ConvertFrom-Json
    $script:LDPlayerSerial = [string]$script:LDPlayerTarget.serial
    $script:LDPlayerHome = [string]$script:LDPlayerTarget.ldPlayerHome
    $script:LDPlayerInstanceIndex = [int]$script:LDPlayerTarget.instanceIndex
} catch {
    throw "Android-dev target config is invalid: $targetConfigPath. $($_.Exception.Message)"
}
if ([string]::IsNullOrWhiteSpace($script:LDPlayerSerial) -or [string]::IsNullOrWhiteSpace($script:LDPlayerHome)) {
    throw "Android-dev target config is incomplete: $targetConfigPath"
}
if ($script:LDPlayerSerial -notmatch '^127\.0\.0\.1:\d+$') {
    throw "Refusing non-loopback Android target: $script:LDPlayerSerial"
}

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME = 'D:\AI\audio\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = 'D:\AI\audio\android-gradle-user-home'
$env:LEIDIAN_SERIAL = $script:LDPlayerSerial
$env:LEIDIAN_HOME = $script:LDPlayerHome

function Assert-LDPlayerTarget {
    if ($env:LEIDIAN_SERIAL -ne $script:LDPlayerSerial) {
        throw "Refusing non-LDPlayer serial: $env:LEIDIAN_SERIAL"
    }
    $ldconsole = Join-Path $env:LEIDIAN_HOME 'ldconsole.exe'
    if (-not (Test-Path $ldconsole)) {
        throw "LDPlayer ldconsole.exe not found: $ldconsole"
    }
    if (-not (Get-Process -Name dnplayer -ErrorAction SilentlyContinue)) {
        throw 'LDPlayer is not running'
    }
    $instancePrefix = "$($script:LDPlayerInstanceIndex),"
    $instance = & $ldconsole list2 | Where-Object { $_.StartsWith($instancePrefix) } | Select-Object -First 1
    $fields = $instance -split ','
    if ($fields.Count -lt 5 -or $fields[4] -ne '1') {
        throw "LDPlayer instance $($script:LDPlayerInstanceIndex) is not reported as running"
    }
    $adb = Join-Path $env:LEIDIAN_HOME 'adb.exe'
    $transportBootSerial = ((& $adb -s $env:LEIDIAN_SERIAL shell getprop ro.boot.serialno) -join "`n").Trim()
    $instanceBootSerial = ((& $ldconsole adb --index $script:LDPlayerInstanceIndex --command 'shell getprop ro.boot.serialno') -join "`n").Trim()
    if ([string]::IsNullOrWhiteSpace($transportBootSerial) -or [string]::IsNullOrWhiteSpace($instanceBootSerial)) {
        throw 'Unable to read the LDPlayer boot serial for target validation'
    }
    if ($transportBootSerial -notmatch '^[0-9A-Za-z_-]+$' -or $instanceBootSerial -notmatch '^[0-9A-Za-z_-]+$') {
        throw 'LDPlayer boot serial validation returned unexpected output'
    }
    if ($transportBootSerial -ne $instanceBootSerial) {
        throw "ADB target $env:LEIDIAN_SERIAL does not match LDPlayer instance $($script:LDPlayerInstanceIndex)"
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
