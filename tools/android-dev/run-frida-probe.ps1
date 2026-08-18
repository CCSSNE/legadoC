param(
    [string]$ClassName = '',
    [string]$MethodName = '',
    [string]$ActivityClass = '',
    [string[]]$StringExtra = @(),
    [switch]$InvokeOnce,
    [switch]$Deoptimize,
    [switch]$LogArguments,
    [int]$DurationSeconds = 5
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

if ($DurationSeconds -lt 1 -or $DurationSeconds -gt 30) {
    throw 'DurationSeconds must be between 1 and 30'
}
if (-not [string]::IsNullOrWhiteSpace($MethodName) -and [string]::IsNullOrWhiteSpace($ClassName)) {
    throw 'MethodName requires ClassName'
}
if ($InvokeOnce -and [string]::IsNullOrWhiteSpace($MethodName)) {
    throw 'InvokeOnce requires MethodName'
}

Assert-LDPlayerTarget
$adb = Join-Path $env:LEIDIAN_HOME 'adb.exe'
$python = Join-Path $script:RepoRoot '.android-dev-venv\Scripts\python.exe'
$server = Join-Path $PSScriptRoot 'bin\frida-server-17.17.0-android-x86_64'
$remoteServer = '/data/local/tmp/legadoc-frida-server'
if (-not (Test-Path $server)) { throw "Frida server not found: $server" }
$serverPid = $null

& $adb connect $env:LEIDIAN_SERIAL | Out-Null
if ((& $adb -s $env:LEIDIAN_SERIAL get-state).Trim() -ne 'device') {
    throw 'LDPlayer transport is not ready'
}
$kernelArch = (& $adb -s $env:LEIDIAN_SERIAL shell uname -m).Trim()
if ($kernelArch -ne 'x86_64') {
    throw "Bundled Frida server requires x86_64 LDPlayer kernel; found $kernelArch"
}

try {
    $occupied = & $adb -s $env:LEIDIAN_SERIAL shell "su -c 'ss -lntp | grep 27042'"
    if (-not [string]::IsNullOrWhiteSpace(($occupied -join ''))) {
        throw "Frida port 27042 is already occupied: $occupied"
    }
    & $adb -s $env:LEIDIAN_SERIAL push $server $remoteServer | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Frida server push failed' }
    & $adb -s $env:LEIDIAN_SERIAL shell chmod 700 $remoteServer | Out-Null
    & $adb -s $env:LEIDIAN_SERIAL shell `
        "su -c 'nohup $remoteServer -D >/data/local/tmp/legadoc-frida.log 2>&1 &'" | Out-Null
    Start-Sleep -Milliseconds 700
    $listener = & $adb -s $env:LEIDIAN_SERIAL shell "su -c 'ss -lntp | grep 27042'"
    $pidMatch = [regex]::Match(($listener -join "`n"), 'pid=(\d+)')
    if (-not $pidMatch.Success) { throw 'Root Frida server did not open port 27042' }
    $serverPid = [int]$pidMatch.Groups[1].Value
    $uidLine = & $adb -s $env:LEIDIAN_SERIAL shell "su -c 'grep ^Uid: /proc/$serverPid/status'"
    if (($uidLine -join ' ') -notmatch '^Uid:\s+0\s') {
        throw "Frida server is not root: $uidLine"
    }

    $arguments = @(
        (Join-Path $PSScriptRoot 'frida_probe.py'),
        '--serial', $env:LEIDIAN_SERIAL,
        '--duration', [string]$DurationSeconds
    )
    if (-not [string]::IsNullOrWhiteSpace($ClassName)) {
        $arguments += @('--class-name', $ClassName)
    }
    if (-not [string]::IsNullOrWhiteSpace($MethodName)) {
        $arguments += @('--method-name', $MethodName)
    }
    if ($InvokeOnce) {
        $arguments += '--invoke-once'
    }
    if ($Deoptimize) {
        $arguments += '--deoptimize'
    }
    if ($LogArguments) {
        $arguments += '--log-arguments'
    }
    if (-not [string]::IsNullOrWhiteSpace($ActivityClass)) {
        $arguments += @('--activity-class', $ActivityClass)
    }
    foreach ($extra in $StringExtra) {
        $arguments += @('--string-extra', $extra)
    }
    & $python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Frida probe failed with exit code $LASTEXITCODE" }
} finally {
    # Killing the server normally prints "Terminated" on stderr. Cleanup is
    # best-effort and must not replace the probe's real result with that message.
    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $adb -s $env:LEIDIAN_SERIAL forward --remove tcp:27042 2>&1 | Out-Null
        if ($null -ne $serverPid) {
            & $adb -s $env:LEIDIAN_SERIAL shell "su -c 'kill $serverPid'" 2>&1 | Out-Null
        }
        & $adb -s $env:LEIDIAN_SERIAL shell rm -f $remoteServer 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
}
