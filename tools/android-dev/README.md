# Android development probes

Load the environment in the current PowerShell session:

```powershell
. .\tools\android-dev\env.ps1
```

Verify the explicit LDPlayer target and Python tools:

```powershell
.\tools\android-dev\verify-env.ps1
```

The sole tool target is maintained in `target.json`. Every PowerShell and Python entry point validates its loopback ADB transport against the boot serial of the running LDPlayer instance 0 before it can operate. Use uiautomator2 for UI state and the managed Frida probe for runtime objects:

```powershell
.\.android-dev-venv\Scripts\python.exe .\tools\android-dev\uiauto_probe.py
.\tools\android-dev\run-frida-probe.ps1
.\tools\android-dev\run-frida-probe.ps1 -ClassName io.legado.app.utils.SurfaceBackdrop
.\tools\android-dev\run-frida-probe.ps1 -ClassName io.legado.app.utils.SurfaceBackdrop -MethodName apply
.\tools\android-dev\run-frida-probe.ps1 -ClassName io.legado.app.ui.main.MainActivity -MethodName isSidebarMode -InvokeOnce
.\tools\android-dev\run-frida-probe.ps1 -ClassName io.legado.app.utils.SurfaceBackdrop -MethodName installResult -LogArguments
.\tools\android-dev\run-frida-probe.ps1 -ActivityClass io.legado.app.ui.config.ConfigActivity -StringExtra 'configTag=themeConfig'
```

Install an existing appC artifact or collect traces:

```powershell
.\tools\android-dev\install-appc.ps1
.\tools\android-dev\capture-perfetto.ps1 -DurationSeconds 5
.\tools\android-dev\capture-winscope-android14.ps1 -DurationSeconds 5
```

The Winscope wrapper starts Perfetto and WindowManager tracing together so both files cover one reproduction. This LDPlayer user build rejects WindowManager time-series tracing, so the wrapper explicitly records `window-surface-snapshots` and captures Window/Surface proto snapshots around the synchronized Perfetto window; it must not be described as a true WM trace. Outputs under `test-records\android-dev` are evidence files, not source artifacts.

Method tracing is opt-in, preserves the original return path, and runs for at most 30 seconds. `-InvokeOnce` is only for a reviewed, zero-argument method with no side effects. `-Deoptimize` deoptimizes the current app Java runtime and should only be used when ART optimization is suspected of hiding hooks. Frida 17 agents explicitly load the Java bridge bundled with `frida-tools`; a missing bridge, JavaScript error, or missing readiness event fails the probe. The wrapper always removes the emulator-side Frida server afterward.

`-LogArguments` is for targeted runtime diagnosis only. It records compact argument descriptions (including Bitmap size/recycle state) and must be limited to the suspected class and method.

`-ActivityClass` starts an app-owned, non-exported activity from the target app process. It rejects external component names and only accepts `key=value` string extras.

LDPlayer 14 does not provide the `monkey` shell command. Resolve and launch the app with `cmd package resolve-activity` plus `am start`; do not make the probes depend on `monkey`.
