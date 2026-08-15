$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

$adb = Join-Path $env:LEIDIAN_HOME 'adb.exe'
$ldconsole = Join-Path $env:LEIDIAN_HOME 'ldconsole.exe'
$python = Join-Path $script:RepoRoot '.android-dev-venv\Scripts\python.exe'
$fridaServer = Join-Path $PSScriptRoot 'bin\frida-server-17.17.0-android-x86_64'

Assert-LDPlayerTarget

& $adb connect $env:LEIDIAN_SERIAL | Out-Null
if ((& $adb -s $env:LEIDIAN_SERIAL get-state).Trim() -ne 'device') { throw "LDPlayer transport is not ready: $env:LEIDIAN_SERIAL" }

$model = (& $adb -s $env:LEIDIAN_SERIAL shell getprop ro.product.model).Trim()
$release = (& $adb -s $env:LEIDIAN_SERIAL shell getprop ro.build.version.release).Trim()
$abi = (& $adb -s $env:LEIDIAN_SERIAL shell getprop ro.product.cpu.abi).Trim()
$qemu = (& $adb -s $env:LEIDIAN_SERIAL shell getprop ro.kernel.qemu).Trim()
if ([string]::IsNullOrWhiteSpace($model) -or [string]::IsNullOrWhiteSpace($abi)) { throw 'LDPlayer properties are unavailable' }
if (-not (Test-Path -LiteralPath $fridaServer)) { throw "Frida server is missing: $fridaServer" }
$qemuLabel = if ([string]::IsNullOrWhiteSpace($qemu)) { '<unset; LDPlayer process+ldconsole used>' } else { $qemu }

& $python -c "import importlib.metadata as m, pathlib, frida, frida_tools, uiautomator2, adbutils; bridge=pathlib.Path(frida_tools.__file__).parent/'bridges'/'java.js'; assert bridge.is_file(), bridge; print('uiautomator2', m.version('uiautomator2')); print('adbutils', m.version('adbutils')); print('frida', frida.__version__); print('frida-java-bridge', bridge)"

Write-Output "Environment verification passed"
Write-Output "  LDPlayer: $model / Android $release / $abi / ro.kernel.qemu=$qemuLabel"
Write-Output "  Perfetto: $((& $adb -s $env:LEIDIAN_SERIAL shell command -v perfetto).Trim())"
Write-Output "  APK output: $script:RepoRoot\app\build\outputs\apk\app\c"
