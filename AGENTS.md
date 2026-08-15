# 阅读 C / legadoC 项目总则

> 本文件是项目唯一的长期规则来源。它只保留可复用的原则、流程、环境约束和当前交付状态；一次性排障过程、界面细节、截图和临时记录不写入这里。
> 规则只写在 `AGENTS.md`。`docs` 下仅保留 `api.md` 与截图。

## 1. 核心工作原则

每次开始编程前，先重申并遵守以下原则：

> 解决根本问题，拒绝任何兜底；有问题，直接暴露。统一维护、统一修复，避免特殊代码不断膨胀。鼓励调查，鼓励详细日志和探针，鼓励联网搜索。

具体要求：

- 先定位事实、边界和根因，再修改代码；不能用静默回退、吞异常、默认值补丁或仅覆盖症状的分支掩盖问题。
- 相同问题应收敛到共同抽象、共同入口或共同数据源。新增特殊逻辑前，先证明现有统一路径无法正确表达该需求。
- 结论必须区分“已由证据确认”和“仍属假设”。复杂问题要补足日志、探针、截图或 trace，使后续排查可以复现。
- 任何失败都必须说明原因和下一步。构建异常在解决后记录现象、根因、修复方式和是否交付；只把能长期复用的结论保留在本文件，并及时修正或删除失效规则。

## 2. 设备与测试边界

### 真机禁令

- 绝对禁止对用户真机执行任何操作，包括所有 `adb` 子命令、截图、点击、安装、卸载、push/pull 和 shell。
- 真机问题只能依据用户描述、代码和用户提供的日志排查；高级调试工具不是例外。

### 雷电模拟器

- APK 安装、运行和调试只使用雷电模拟器（LDPlayer），路径为 `F:\leidian\LDPlayer14\dnplayer.exe`。未启动时可尝试启动；失败则请用户手动打开。
- 每条 `adb` 命令都必须显式带模拟器序列号，例如 `-s emulator-5554` 或 `-s 127.0.0.1:5555`。执行前确认目标确为模拟器；不确定时停止，禁止裸 `adb`。
- 真实小说优先用于阅读功能验证。`C:\Users\user\Documents\leidian14\Pictures` 与模拟器 Pictures 目录互通，可作为导入素材。

### 验证闭环

每次代码改动都按以下闭环执行：正式编译 `appC` APK -> 安装到已确认的雷电模拟器 -> 复现并回归验证。

- 模拟器不可用时不得改用真机。
- 崩溃或行为异常时，先收集日志和复现证据，定位根因后修复，再重新正式编译和回归；不能报告未经验证的修复。
- UI 改动必须覆盖受影响的交互、显示、主题/状态切换和关闭重开等生命周期，而不是只确认一张静态截图。

## 3. 构建、版本与产物

### 不可变交付约束

- 代码改动只能用正式 `appC` 变体验证与交付：`app\build\outputs\apk\app\c`。禁止以中间 Gradle 任务、debug APK 或改名旧包充当验证/交付物。
- 覆盖安装前必须显式传入 `VERSION_CODE` 和 `VERSION_NAME`。新 `VERSION_CODE` 必须比最近一次交付大；`VERSION_NAME` 必须按 GMT+8 编译时刻单调递增，格式为 `3.26.MMddHH`。
- `appC` flavor 会自动添加版本名后缀 `c`，传给 `-PVERSION_NAME` 的值不得包含 `c`。最终必须以 `aapt` 输出为准，产物版本名应为 `3.26.MMddHHc`。
- 编译前先从模拟器已安装包确认版本；模拟器不可用时使用第 6 节的最近交付基线。确认新版本后，只删除 `app\build\outputs\apk\app\c` 中对应的旧 APK，绝不删除宽泛目录或源码。

### 本机环境与正式命令

- JDK: `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`
- Android SDK: `D:\AI\audio\android-sdk`
- Gradle user home: `D:\AI\audio\android-gradle-user-home`
- Gradle wrapper: `8.14.4`; compileSdk: `36`

```powershell
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME = 'D:\AI\audio\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\AI\audio\android-sdk'
$env:GRADLE_USER_HOME = 'D:\AI\audio\android-gradle-user-home'
$env:Path = @(
  "$env:JAVA_HOME\bin",
  "$env:ANDROID_HOME\cmdline-tools\latest\bin",
  "$env:ANDROID_HOME\platform-tools"
) + ($env:Path -split ';') -join ';'

$versionCode = <new-version-code>
$versionName = '3.26.<MMddHH>' # appC automatically appends c
.\gradlew.bat ':app:assembleAppC' '-Pabi=arm64-v8a' "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" --console=plain --warning-mode=summary
```

### 长命令和构建失败

- 任何可能超过 30 秒的命令必须实时监控。每 30 秒以内检查进程是否存活、CPU 是否增长、日志/产物是否更新；停滞时终止并报告，不能无限等待。
- 后台编译须保存 stdout、stderr 和退出码。`cmd /c` 的内联重定向不可靠时，改用 `.bat` 文件启动，不得把空日志误判为正常编译。
- 先阅读实际错误中的文件、行号和异常，再选择修复。不得把源码错误猜成内存问题后盲目重跑。
- 仅在证据指向缓存锁定、守护进程或原生内存问题时，先停止 Gradle，清理残留 Gradle/Kotlin/Java 进程，再用正式 `assembleAppC` 进行最小必要的冷编译诊断，例如 `--no-daemon --max-workers=1 -Dkotlin.incremental=false -Dksp.incremental=false -Dkotlin.compiler.execution.strategy=in-process`。目录清理仅限受影响模块的 `build` 目录。
- 构建无论成功或失败，执行 `.\gradlew.bat --stop` 并按 PID 清理残留构建进程，避免占用内存。
- 2026-08-15：`HeaderlessDialogChrome` 首次正式编译在 `AccentTextView(context)` 失败，因为该控件构造器强制要求 `AttributeSet?`；读取 Kotlin 报错后改为 `AccentTextView(context, null)`，同版本重编译成功。失败包未产出、未交付。动态创建项目自定义 View 时必须先核对构造器签名，不能假定存在单参构造器。
- 2026-08-15：首次启动 10608 构建时，把批处理和退出码写入拼在 `cmd /c` 参数中，Windows 报“文件名、目录名或卷标语法不正确”，没有 Gradle 进程、构建日志或 APK。改为由 `.bat` 自己记录退出码，再以 `Start-Process` 直接启动，构建正常。后台构建的重定向/引号错误必须以“未启动”处理，不能等待或误判为 Gradle 卡死。

### 产物验证

```powershell
$apk = 'D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_<version>.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk
```

交付前确认：包名 `io.legado.app.c`、版本号递增、中文名 `阅读 C`、`arm64-v8a`、产物来自 `appC`，且 `apksigner` 退出码为 0。部分 `META-INF` 条目未受签名保护的提示可接受。

## 4. 工程质量规则

- 无头弹窗的统一策略只负责移除 `Toolbar` 并把菜单动作迁到标准底部操作区；不得以保留空白 Toolbar 伪装“无头”。移除 Toolbar 前必须核对布局测量：原来依赖 Toolbar 固定高度的 `0dp` / weight 内容区，要改成显式的“内容区 + 底部操作区”结构，否则 `wrap_content` Dialog 会塌缩。
- 标准 `AlertDialog` 的标题不能直接追加到 `contentPanel`：该面板是叠放容器，会与选择列表重叠。统一表面路径应将标题和原内容重组为垂直内容列后再隐藏 `topPanel`，使标题成为同一玻璃面上的正文首行，而非独立顶栏。缺少 `contentPanel` 属于结构错误，应直接暴露，不能悄悄丢弃标题或遮住首项。

### 设置默认值

每个设置的界面默认值与实际读取默认值必须一致：

- 界面默认值在 `app\src\main\res\xml\pref_config_*.xml` 的 `android:defaultValue`。
- 实际默认值在 `AppConfig.kt` 及各调用点的 `getPrefBoolean`、`getPrefInt`、`getPrefString`。`getPrefBoolean(key)` 不带默认参数时默认是 `false`。
- 修改任意设置默认值时，全库搜索该 key 的所有读取点，逐一核对类型和值；界面显示与实际行为不一致属于缺陷，不能接受“默认分支行为等价”作为理由。

### 异步 UI 与局部模糊

- 只处理真实浮层表面或明确声明的背景层，禁止扫描控件树猜测目标；找不到可靠目标时应暴露问题，不能扩大为宿主 Activity 全屏模糊或纯色兜底。
- 几何、着色、描边与模糊底图必须由同一表面模型和同一裁剪路径管理。每个浮层实例使用独立背景副本，不能混用可变 Drawable 或叠加互相冲突的形状背景。
- 取图必须在目标和宿主 attach、且几何连续两帧稳定后进行。`PixelCopy` 源矩形必须使用源 Window 坐标并严格相交裁剪；不能用强制最小 1 像素矩形掩盖坐标错误。
- 首次可见前完成背景安装。关闭、换目标、重新显示和尺寸变化要使旧回调失效并释放旧位图；样式更新只更新样式，不应取消同一目标仍有效的取图，回调安装时使用最新样式。
- 禁止 `RenderEffect` 作用于宿主 `decorView`，以及 `setBackgroundBlurRadius` / `FLAG_BLUR_BEHIND` 等整窗模糊路径。若要改变浮层外壳几何，先分离外壳、背景层、内容层并完成模拟器全路径验证。

### 分层调试

- 常规问题先用模拟器 ADB、`uiautomator2`、截图和 logcat。
- 只有常规证据不足，且明确怀疑时序、线程、Window/Surface 合成或运行时调用链时，才升级到 Perfetto、Winscope 或 Frida。
- 高级证据必须围绕同一次复现采集：记录开始时间、操作、结束时间；将 UI 层级、时间线、Window/Surface 与调用证据对齐，明确观察结果、排除项、根因和结构性修复。
- 工具入口为 `tools\android-dev`，输出写入已忽略的 `test-records\android-dev`，不得提交 trace、截图、临时二进制或虚拟环境。雷电 Android 14 不支持的 WindowManager 时间序列 tracing 必须如实标为快照降级模式。
- Frida 仅能连接 `127.0.0.1:5555`，默认只读；方法跟踪须限定包、类、方法和最长 30 秒，不修改参数、字段或返回值。脚本错误、`Java is not defined` 或初始化缺失均为失败；结束后卸载脚本并移除模拟器临时 server。

理想环境操作：
uiautomator2 / ADB
        │
        ▼
──────── AI ────────
 │       │        │
 │       │        └── Perfetto
 │       │            看时间/线程/帧
 │       │
 │       └────────── Winscope trace
 │                    看 Window/Surface
 │
 └────────────────── Frida / AI Debug Probe
                      看真实运行时对象和调用链

## 5. 发布与版本控制

### Release

- 发布前重新执行第 3 节的 APK 验证。tag 必须为 `v<versionName>`，与 APK 版本名一致；`target_commitish` 指向 `own` 分支最新提交。
- Release 正文通过 UTF-8 无 BOM JSON 文件提交：设置 `PYTHONUTF8=1`，用 `json.dump(..., ensure_ascii=False)` 生成，并使用 `curl.exe --data-binary "@<file>"`。不得将中文正文或二进制通过 PowerShell 文本管道传递。
- APK 上传使用 `Content-Type: application/octet-stream` 和 `curl.exe --data-binary @<apk>`。
- 发布后通过 API 和 GitHub 网页复查中文、排版、`draft=false`、`prerelease=false`、tag、目标提交、资产大小和下载 200；发现乱码则用 UTF-8 无 BOM JSON PATCH 后重新复查。

### Git

- 提交前检查 `git status`、`git diff`、`git log`。只暂存本次需要的文件，不提交 APK、构建日志、trace 或临时文件。
- 提交信息简洁且准确，遵循现有仓库风格。

## 6. 当前交付基线

仅保留最近一次已交付版本，下一次覆盖安装必须在此基础上递增：

- `3.26.081530c` / `10609`，2026-08-15，已在雷电模拟器安装。书签无头、局部模糊且窗口无 `DIM_BEHIND`；帮助/日志的 Toolbar 动作迁至底部；标准“图片样式”选择框标题作为正文首行，与首项不重叠。

每次交付后当场更新本节。历史发布信息应从 Git、GitHub Release 或提交记录查询，不在本文件累积。
