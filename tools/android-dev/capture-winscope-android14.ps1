param(
    [int]$DurationSeconds = 5,
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

if ($DurationSeconds -lt 1 -or $DurationSeconds -gt 30) { throw 'DurationSeconds must be between 1 and 30' }
$adb = Join-Path $env:LEIDIAN_HOME 'adb.exe'
Assert-LDPlayerTarget
& $adb -s $env:LEIDIAN_SERIAL connect $env:LEIDIAN_SERIAL | Out-Null
if ((& $adb -s $env:LEIDIAN_SERIAL get-state).Trim() -ne 'device') { throw 'LDPlayer transport is not ready' }

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $script:RepoRoot 'test-records\android-dev'
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$wmOutput = Join-Path $OutputDirectory "wmtrace-$stamp"
New-Item -ItemType Directory -Force -Path $wmOutput | Out-Null

$configTemplate = Join-Path $PSScriptRoot 'config\perfetto-winscope.pbtxt'
$hostConfig = Join-Path $OutputDirectory "perfetto-$stamp.pbtxt"
$hostTrace = Join-Path $OutputDirectory "perfetto-$stamp.pftrace"
$deviceConfig = "/data/local/tmp/legadoC-perfetto-$stamp.pbtxt"
$deviceTrace = "/data/local/tmp/legadoC-perfetto-$stamp.pftrace"
$deviceWindowStart = "/data/local/tmp/legadoC-window-start-$stamp.winscope"
$deviceWindowEnd = "/data/local/tmp/legadoC-window-end-$stamp.winscope"
$deviceSfStart = "/data/local/tmp/legadoC-sf-start-$stamp.winscope"
$deviceSfEnd = "/data/local/tmp/legadoC-sf-end-$stamp.winscope"
$configText = (Get-Content -Raw $configTemplate).Replace('{{DURATION_MS}}', ([string]($DurationSeconds * 1000)))
[IO.File]::WriteAllText($hostConfig, $configText, [Text.UTF8Encoding]::new($false))

# Start Perfetto on-device and wait until its data sources are initialized.
# WindowManager tracing starts only after that acknowledgement, so both traces
# necessarily cover the same user action window.
$wmTracingStarted = $false
try {
    & $adb -s $env:LEIDIAN_SERIAL push $hostConfig $deviceConfig | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Perfetto config push failed' }
    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $perfettoStartOutput = & $adb -s $env:LEIDIAN_SERIAL shell perfetto --background-wait --txt -c $deviceConfig -o $deviceTrace 2>&1
        $perfettoStartExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $perfettoStartOutput | Write-Output
    if ($perfettoStartExit -ne 0) { throw "Unable to start Perfetto: exit=$perfettoStartExit" }

    & $adb -s $env:LEIDIAN_SERIAL shell wm tracing size 32768 | Out-Null
    & $adb -s $env:LEIDIAN_SERIAL shell wm tracing level all | Out-Null
    & $adb -s $env:LEIDIAN_SERIAL shell wm tracing frame | Out-Null
    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $wmStartOutput = & $adb -s $env:LEIDIAN_SERIAL shell wm tracing start 2>&1
        $wmStartExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $wmTraceSupported = $wmStartExit -eq 0 -and
        (($wmStartOutput -join "`n") -notmatch 'not supported|Error:')
    if ($wmTraceSupported) {
        $wmTracingStarted = $true
        $captureMode = 'window-manager-trace'
    } else {
        $captureMode = 'window-surface-snapshots'
        & $adb -s $env:LEIDIAN_SERIAL shell "dumpsys window --proto > $deviceWindowStart"
        & $adb -s $env:LEIDIAN_SERIAL shell "dumpsys SurfaceFlinger --proto > $deviceSfStart"
    }
    Write-Output "Winscope capture active for $DurationSeconds seconds (mode: $captureMode)"
    Start-Sleep -Seconds $DurationSeconds
    if ($wmTraceSupported) {
        # save-for-bugreport only writes while tracing is still active.
        & $adb -s $env:LEIDIAN_SERIAL shell wm tracing save-for-bugreport | Out-Null
        & $adb -s $env:LEIDIAN_SERIAL shell wm tracing stop | Out-Null
        $wmTracingStarted = $false
        & $adb -s $env:LEIDIAN_SERIAL pull /data/misc/wmtrace $wmOutput | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'WindowManager trace pull failed' }
        & $adb -s $env:LEIDIAN_SERIAL shell "dumpsys SurfaceFlinger --proto > $deviceSfEnd"
        & $adb -s $env:LEIDIAN_SERIAL pull $deviceSfEnd (Join-Path $wmOutput 'surfaceflinger-end.winscope') | Out-Null
    } else {
        & $adb -s $env:LEIDIAN_SERIAL shell "dumpsys window --proto > $deviceWindowEnd"
        & $adb -s $env:LEIDIAN_SERIAL shell "dumpsys SurfaceFlinger --proto > $deviceSfEnd"
        $snapshots = @(
            @($deviceWindowStart, (Join-Path $wmOutput 'window-start.winscope')),
            @($deviceWindowEnd, (Join-Path $wmOutput 'window-end.winscope')),
            @($deviceSfStart, (Join-Path $wmOutput 'surfaceflinger-start.winscope')),
            @($deviceSfEnd, (Join-Path $wmOutput 'surfaceflinger-end.winscope'))
        )
        foreach ($snapshot in $snapshots) {
            & $adb -s $env:LEIDIAN_SERIAL pull $snapshot[0] $snapshot[1] | Out-Null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $snapshot[1]) -or (Get-Item $snapshot[1]).Length -eq 0) {
                throw "Winscope snapshot failed: $($snapshot[1])"
            }
        }
    }
    [IO.File]::WriteAllText(
        (Join-Path $wmOutput 'capture-mode.txt'),
        $captureMode,
        [Text.UTF8Encoding]::new($false)
    )
    $traceReady = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        & $adb -s $env:LEIDIAN_SERIAL shell test -s $deviceTrace
        if ($LASTEXITCODE -eq 0) {
            $traceReady = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $traceReady) { throw 'Synchronized Perfetto trace was not finalized' }
    & $adb -s $env:LEIDIAN_SERIAL pull $deviceTrace $hostTrace | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $hostTrace) -or (Get-Item $hostTrace).Length -eq 0) {
        throw 'Synchronized Perfetto trace pull failed or produced an empty trace'
    }
    Write-Output "Synchronized Perfetto trace: $hostTrace"
    Write-Output "Winscope evidence ($captureMode): $wmOutput"
} finally {
    if ($wmTracingStarted) {
        & $adb -s $env:LEIDIAN_SERIAL shell wm tracing stop | Out-Null
    }
    & $adb -s $env:LEIDIAN_SERIAL shell rm -f $deviceConfig $deviceTrace `
        $deviceWindowStart $deviceWindowEnd $deviceSfStart $deviceSfEnd | Out-Null
}
