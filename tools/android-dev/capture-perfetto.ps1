param(
    [int]$DurationSeconds = 5,
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

if ($DurationSeconds -lt 1 -or $DurationSeconds -gt 30) { throw 'DurationSeconds must be between 1 and 30' }
$adb = Join-Path $env:LEIDIAN_HOME 'adb.exe'
$configTemplate = Join-Path $PSScriptRoot 'config\perfetto-winscope.pbtxt'
if (-not (Test-Path $configTemplate)) { throw "Perfetto config not found: $configTemplate" }

Assert-LDPlayerTarget
& $adb connect $env:LEIDIAN_SERIAL | Out-Null
if ((& $adb -s $env:LEIDIAN_SERIAL get-state).Trim() -ne 'device') { throw 'LDPlayer transport is not ready' }

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $script:RepoRoot 'test-records\android-dev'
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$hostConfig = Join-Path $OutputDirectory "perfetto-$stamp.pbtxt"
$hostTrace = Join-Path $OutputDirectory "perfetto-$stamp.pftrace"
$deviceConfig = "/data/local/tmp/legadoC-perfetto-$stamp.pbtxt"
$deviceTrace = "/data/local/tmp/legadoC-perfetto-$stamp.pftrace"
$configText = (Get-Content -Raw $configTemplate).Replace('{{DURATION_MS}}', ([string]($DurationSeconds * 1000)))
[IO.File]::WriteAllText($hostConfig, $configText, [Text.UTF8Encoding]::new($false))

try {
    & $adb -s $env:LEIDIAN_SERIAL push $hostConfig $deviceConfig
    if ($LASTEXITCODE -ne 0) { throw 'Perfetto config push failed' }
    # adb forwards Perfetto's harmless "No PTY" warning on stderr. In a
    # PowerShell background job, ErrorActionPreference=Stop would otherwise
    # mark the whole job failed even when Perfetto exits 0.
    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $perfettoOutput = & $adb -s $env:LEIDIAN_SERIAL shell perfetto --txt -c $deviceConfig -o $deviceTrace 2>&1
        $perfettoExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $perfettoOutput | Write-Output
    if ($perfettoExitCode -ne 0) { throw "Perfetto failed with exit code $perfettoExitCode" }
    & $adb -s $env:LEIDIAN_SERIAL pull $deviceTrace $hostTrace
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $hostTrace) -or (Get-Item $hostTrace).Length -eq 0) {
        throw 'Perfetto trace pull failed or produced an empty trace'
    }
} finally {
    & $adb -s $env:LEIDIAN_SERIAL shell rm -f $deviceConfig $deviceTrace | Out-Null
}

Write-Output "Perfetto trace: $hostTrace"
