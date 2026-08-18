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

### 请求范围与执行边界

- 用户请求按字面含义执行，不推定隐藏含义，不擅自扩展任务范围，不以“顺便完善”“完整闭环”或其他理由追加用户未要求的工作。
- 用户要求修改某个文件、整理代码或执行其他具体动作时，只完成明确要求的事项。完成的独立修改按第 5 节 Git 规则自动分类提交；自动提交属于修改工作的固定收尾，不视为扩大任务范围，也不需要用户另行要求。
- 只有用户明确要求正式编译、安装或正式模拟器回归时，才执行其明确要求的对应动作；不得因为修改了代码或配置文件就自行启动这些流程。
- 排障默认包含诊断、形成原因与解决方案、动态实施方案并回归确认；具体按“诊断与动态验证”执行。

## 2. 设备与测试边界

### 真机禁令

- 绝对禁止对用户真机执行任何操作，包括所有 `adb` 子命令、截图、点击、安装、卸载、push/pull 和 shell。
- 真机问题只能依据用户描述、代码和用户提供的日志排查；高级调试工具不是例外。

### 雷电模拟器

- APK 安装、运行和调试只使用雷电模拟器（LDPlayer），路径为 `F:\leidian\LDPlayer14\dnplayer.exe`。未启动时可尝试启动；失败则请用户手动打开。
- 所有 ADB 调试目标只能是雷电模拟器；执行前通过 `tools\android-dev\target.json` 的实例 0 校验确认目标确为模拟器；不确定时停止，禁止裸 `adb`。
- 真实小说优先用于阅读功能验证。`C:\Users\user\Documents\leidian14\Pictures` 与模拟器 Pictures 目录互通，可作为导入素材。

### 验证闭环

本节只在用户明确要求对应验证动作时适用。用户明确要求完整验证闭环时，按以下流程执行：正式编译 `appC` APK -> 安装到已确认的雷电模拟器 -> 复现并回归验证。用户只要求其中一项时，只执行该项，不自行补充其余步骤。

- 模拟器不可用时不得改用真机。
- 崩溃或行为异常时，先按“诊断与动态验证”完成原因与解决方案的动态确认；只有用户已明确要求正式验证闭环时，才在正式修复后继续正式编译和回归。不能报告未经验证的修复。
- UI 改动必须覆盖受影响的交互、显示、主题/状态切换和关闭重开等生命周期，而不是只确认一张静态截图。

## 3. 构建、版本与产物

### 不可变交付约束

- 用户明确要求正式验证或交付代码改动时，只能使用正式 `appC` 变体：`app\build\outputs\apk\app\c`。禁止以中间 Gradle 任务、debug APK 或改名旧包充当正式验证/交付物。
- 所有正式编译必须包含目标分支在编译启动前已经提交的全部源码和资源改动，禁止只选取自己的提交，或从落后于目标分支最新提交的 detached worktree、临时快照和源码副本生成验证与交付 APK。未提交改动视为尚未完成，不得进入正式编译。编译前必须记录目标分支与 `HEAD`，构建用的干净工作区必须与该 `HEAD` 完全一致；编译完成后再次检查目标分支，若期间出现新提交，必须基于新的分支头重新编译。
- 覆盖安装前必须显式传入 `VERSION_CODE` 和 `VERSION_NAME`。`VERSION_NAME` 仅用于展示，格式为 `3.26.MMddHH`：其中 `3.26` 后面的 `MMddHH` 是 UTC（世界零点）编译时间（月份、日期、小时），因此版本名按 UTC 时间记录。
- 如果 APK 或本文件基线里的 `versionName` 时间部分异常、跑到未来，直接按正确的 UTC 时间修正即可；这不会触发降级，因为安卓升级、降级判断只看 `versionCode`。
- `VERSION_CODE` 是与时间无关的独立整数序号，不是时间戳，不得按日期解读或计算；每次交付只需比最近一次交付的 `VERSION_CODE` 大，保持单调递增。
- `appC` flavor 会自动添加版本名后缀 `c`，传给 `-PVERSION_NAME` 的值不得包含 `c`。最终必须以 `aapt` 输出为准，产物版本名应为 `3.26.MMddHHc`。
- 若只是 APK 文件名末尾的 `c` 多一个或少一个，或文件名中的 `versionName` 文本写错，而 `aapt dump badging` 确认 APK 内部 `versionName`、`versionCode` 和包名均正确，直接修正文件名或记录即可，不得为此重新编译；只有 APK 内部元数据确实错误时才重编译。
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

$versionCode = <new-version-code> # 独立递增整数，不是时间戳
$versionName = '3.26.<MMddHH>' # <MMddHH> uses UTC; appC automatically appends c
.\gradlew.bat ':app:assembleAppC' '-Pabi=arm64-v8a' "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" --console=plain --warning-mode=summary
```

### 长命令和构建失败

- 任何可能超过 30 秒的命令必须实时监控。每 30 秒以内检查进程是否存活、CPU 是否增长、日志/产物是否更新；停滞时终止并报告，不能无限等待。
- `assembleAppC` 及任何可能超过 30 秒的编译必须通过 `Start-Process` 或独立 `.bat` 在后台启动；禁止在当前工具会话前台直接运行长时间 Gradle 编译，也禁止用阻塞等待伪装成后台编译。
- 后台启动的 `PowerShell`、`cmd`、Gradle 或包装脚本必须显式使用 `Start-Process -WindowStyle Hidden`；禁止弹出可见控制台窗口。构建 stdout、stderr、退出码和 APK 必须写入文件，禁止把二进制内容输出到文本终端。
- 后台编译启动后必须立即返回 PID、日志路径和启动参数；后续通过独立的进程轮询检查存活、CPU、stdout、stderr 和 APK 产物，每 30 秒以内检查一次，不能让当前会话持续占用等待编译完成。
- 后台编译须保存 stdout、stderr 和退出码。`cmd /c` 的内联重定向不可靠时，改用 `.bat` 文件启动，不得把空日志或启动器已退出误判为编译成功。
- 用户中断或工具会话结束后，必须先检查并清理仍属于本次构建的 Gradle/Kotlin/Java PID，再报告构建结果；不得遗留后台编译进程。
- 先阅读实际错误中的文件、行号和异常，再选择修复。不得把源码错误猜成内存问题后盲目重跑。
- 仅在证据指向缓存锁定、守护进程或原生内存问题时，先停止 Gradle，清理残留 Gradle/Kotlin/Java 进程，再用正式 `assembleAppC` 进行最小必要的冷编译诊断，例如 `--no-daemon --max-workers=1 -Dkotlin.incremental=false -Dksp.incremental=false -Dkotlin.compiler.execution.strategy=in-process`。目录清理仅限受影响模块的 `build` 目录。
- 构建无论成功或失败，执行 `.\gradlew.bat --stop` 并按 PID 清理残留构建进程，避免占用内存。
- 2026-08-15：`HeaderlessDialogChrome` 首次正式编译在 `AccentTextView(context)` 失败，因为该控件构造器强制要求 `AttributeSet?`；读取 Kotlin 报错后改为 `AccentTextView(context, null)`，同版本重编译成功。失败包未产出、未交付。动态创建项目自定义 View 时必须先核对构造器签名，不能假定存在单参构造器。
- 2026-08-15：首次启动 10608 构建时，把批处理和退出码写入拼在 `cmd /c` 参数中，Windows 报“文件名、目录名或卷标语法不正确”，没有 Gradle 进程、构建日志或 APK。改为由 `.bat` 自己记录退出码，再以 `Start-Process` 直接启动，构建正常。后台构建的重定向/引号错误必须以“未启动”处理，不能等待或误判为 Gradle 卡死。
- 2026-08-16：新增被 XML 引用的字符串只写入 `values-zh` 会在 `appC` 资源链接时被移除，最终报“resource string not found”。所有新增字符串必须先定义在默认 `values/strings.xml`，再补充语言覆盖；首次链接失败包不产出，补齐默认资源后以同一版本重编译。

### 产物验证

```powershell
$apk = 'D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_<version>.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk
```

交付前确认：包名 `io.legado.app.c`、版本号递增、中文名 `阅读 C`、`arm64-v8a`、产物来自 `appC`，且 `apksigner` 退出码为 0。部分 `META-INF` 条目未受签名保护的提示可接受。

## 4. 工程质量规则

- 无头弹窗的统一策略只负责移除 `Toolbar` 并把菜单动作迁到标准底部操作区；不得以保留空白 Toolbar 伪装“无头”。移除 Toolbar 前必须核对布局测量：原来依赖 Toolbar 固定高度的 `0dp` / weight 内容区，要改成显式的“内容区 + 底部操作区”结构，否则 `wrap_content` Dialog 会塌缩。
- 无头迁移器向 `ConstraintLayout` 加入底部操作区时，所有原先 `bottomToBottom=parent` 的内容必须统一改为约束到 footer 顶部；禁止仅增加 parent padding 伪造预留空间，否则滚动内容会与按钮重叠。`dialog_content_edit` 于 2026-08-15 以此规则完成回归。
- 标准 `AlertDialog` 的标题不能直接追加到 `contentPanel`：该面板是叠放容器，会与选择列表重叠。统一表面路径应将标题和原内容重组为垂直内容列后再隐藏 `topPanel`，使标题成为同一玻璃面上的正文首行，而非独立顶栏。使用 `setCustomView` 时内容位于 `customPanel`；标题迁移后只能保持 `customPanel` 或 `contentPanel` 之一作为中段，禁止额外启用另一个面板挤占 `buttonPanel` 的测量空间。缺少相应面板属于结构错误，应直接暴露，不能悄悄丢弃标题或遮住首项。

### UI 内核与浮层规范

本项目的 UI 内核不是一套普通页面和另一套弹窗页面，而是四层单向组合。所有新 UI 必须先在此树中归类；业务页面只能使用下层能力，不能反向改写或复制下层逻辑。

```text
主题语义层
ThemeStore / ThemeUtils / UiCorner
    └─ UI、阅读、Dialog 三组颜色、透明度、圆角和描边语义
        │
表面描述与渲染层
SurfaceStyle / SurfaceStyles / SurfaceDrawable
    └─ 同一裁剪路径绘制模糊底图、tint、描边和几何
        │
表面生命周期层
SurfaceBackdrop
    └─ 稳定几何、PixelCopy、局部模糊、代际丢弃和位图回收
        │
宿主适配与内容层
BaseDialogFragment / BasePrefDialogFragment / BaseBottomSheetDialogFragment
AndroidAlertBuilder / SurfacePopupMenu / 阅读页显式浮层
    └─ Feature 的业务内容、操作和布局
```

#### 首先分类，不得按“看起来像”处理

| 类型 | 统一入口 | 表面规则 |
|---|---|---|
| 普通 Activity / Fragment 页面与页内控件 | `ThemeStore`、`UiCorner`、现有主题 View/样式 | 只使用 UI 组样式；不是模糊浮层，禁止为整页安装 `SurfaceBackdrop`。 |
| 普通模态 Dialog | `BaseDialogFragment` | 声明真实可见表面（优先 `vw_bg`），由基类安装 Dialog 表面。 |
| Preference Dialog | `BasePrefDialogFragment` 或现有 preference adapter | 走同一 Dialog 表面与无头 Alert 规则。 |
| 底部 Sheet / 阅读设置 Sheet | `BaseBottomSheetDialogFragment`；阅读页使用 `BaseReaderSheet*` | 仅上角几何；阅读色彩只能来自 `ReaderSheetStyle`。 |
| 简单确认、选择、输入框 | `alert` / `selector` / `AndroidAlertBuilder` | 由 `applyAlertSurface()` 处理 AppCompat 面板和无头标题。 |
| 右上角更多、列表行更多等 PopupWindow 菜单 | `SurfacePopupMenu` 或 `View.showPopupMenu` | 应用拥有唯一可见外壳，显示前完成其局部表面准备。 |
| 阅读页 Activity 内的主菜单、搜索菜单、文本操作浮层 | 调用方声明的专用背景层 | 这是同窗口浮层，不是 Dialog；只能刷新明确命名的目标表面。 |

Activity 页面标题和正文标题不是“弹窗头”，不得为追求无头规则而删除。无头规则只适用于广义浮层的独立顶栏：Dialog、Alert、Sheet、PopupWindow 和阅读页浮层都不得新增 `Toolbar` / `TitleBar` 顶栏；操作应放在内容内的标准底部操作区。标题有业务语义时只能作为正文首行，不能恢复独立 chrome。

#### 只有一个表面内核

- `SurfaceStyle` 只描述视觉：tint、圆角、描边、模糊半径；它不得知道窗口类型、布局树或业务状态。
- `SurfaceBackdrop` 是唯一可做 PixelCopy、模糊、稳定几何等待、显示代际和位图回收的地方。`SurfaceDrawable` 是唯一把底图、tint、描边绘入同一裁剪路径的地方。
- 每个浮层必须显式声明一个真实、唯一的可见表面。不能扫描控件树猜目标，不能把内容按钮、列表或宿主 decor 当作表面，也不能缓存宿主整页后按猜测坐标裁剪。
- UI、阅读、Dialog 的颜色和透明度只能经 `UiCorner` / `SurfaceStyles` / `ReaderSheetStyle` 取得；Feature 不得重算 alpha、圆角、描边、模糊半径或写另一套玻璃颜色公式。
- `updateStyle()` 只更新同一目标的样式，不得中断该目标在途取图；关闭、换目标、重新显示和尺寸变化才创建新代际。Feature 不得自行管理另一套 generation 或 Bitmap 生命周期。

#### 新代码的强制入口

- 新的自定义模态框只能继承相应 `Base*DialogFragment`。新的简单 Alert 只能走 `alert` / `selector` / `AndroidAlertBuilder`；新的菜单只能走 `SurfacePopupMenu` 或其扩展入口。
- 新的阅读页浮层必须先声明“宿主 Window、唯一背景层、显示前准备点、关闭点、尺寸变化点”，然后复用 `SurfaceBackdrop`。这些条件无法表达时，先扩展内核/宿主适配器并完成全路径验证，禁止在 Feature 内新建 `xxxBlur`、`xxxGlass`、`xxxPopup` 或私有表面助手。
- 需要跨两个以上 Feature 或两种以上宿主复用的视觉/交互模式，提升到 `lib/theme`、`lib/theme/surface`、`lib/dialogs` 或 `ui/widget` 的现有内核旁；只属于一个 Feature 的业务内容留在 Feature 内，但仍使用核心表面和样式。
- 现存直接 `Dialog`、`PopupWindow` 或第三方窗口类属于迁移存量，不是新代码模板。修改它们时优先接入上述入口；确有宿主限制时，先记录限制和适配方案，不能复制一份私有实现。

#### 绝对禁止

- 禁止给宿主 Activity `decorView` 做全局 `RenderEffect`；禁止 `FLAG_BLUR_BEHIND`、`setBackgroundBlurRadius`、`DIM_BEHIND` 或任何系统整窗变暗来替代局部表面。
- 禁止反射 PopupWindow 私有字段、共享可变背景 Drawable、叠加“矩形 Bitmap + 另一层圆角颜色”背景，或以透明/纯色/全屏模糊作为取图失败的 Feature 级兜底。
- 禁止在新 Dialog 布局中新增 `Toolbar` / `TitleBar`，禁止新建特定页面的 alpha、blur、corner、surface-color 常量或 `when (页面名)` 特例。
- 禁止为绕过本规范添加新的 suppress、静默 catch、默认回退目标或吞掉表面安装错误。内核无法表达的需求必须直接暴露并先修内核。

#### UI 变更验收清单

- [ ] 已明确它是普通 UI、Dialog、Preference、Sheet、Alert、PopupWindow 还是阅读页同窗口浮层，并使用了表中唯一入口。
- [ ] 浮层已明确真实背景层；目标 attach、连续两帧几何稳定后才取图，首次可见前背景已安装。
- [ ] 没有全局模糊、系统 DIM、私有反射、Feature 自建表面算法、独立 Bitmap 生命周期或页面专属兜底。
- [ ] Dialog/Alert/Popup 没有独立头栏；需要的操作在标准底部区，关闭、重开、主题变化和尺寸变化都不会让旧回调覆盖新表面。
- [ ] 已按当前授权完成验证：未明确要求正式回归时，可被运行时等价映射的逻辑改动优先用 Frida hot-patch 注入当前运行进程，并配合截图、uiautomator2、logcat 做针对性验证；资源/XML、Manifest、Gradle、native 或类结构等不能被可靠等价注入的改动必须明确标记为“Frida 未完整验证”，不得伪装成正式回归。明确要求正式回归时，再按正式 APK 路径完成完整检查。

### 设置默认值

每个设置的界面默认值与实际读取默认值必须一致：

- 界面默认值在 `app\src\main\res\xml\pref_config_*.xml` 的 `android:defaultValue`。
- 实际默认值在 `AppConfig.kt` 及各调用点的 `getPrefBoolean`、`getPrefInt`、`getPrefString`。`getPrefBoolean(key)` 不带默认参数时默认是 `false`。
- 修改任意设置默认值时，全库搜索该 key 的所有读取点，逐一核对类型和值；界面显示与实际行为不一致属于缺陷，不能接受“默认分支行为等价”作为理由。
- 背景图这类文件型默认值不能写成某台设备的绝对路径。必须把素材随 APK 提供，并由统一主题初始化在 `applyDayNightInit()` 前复制到应用私有目录，再为尚未配置的日间/夜间 key 写入该稳定路径。`backgroundImage` / `backgroundImageNight` 缺失表示从未配置；空字符串表示用户明确移除背景，后续启动不得覆盖。
- `uiLayoutAlpha` 的值表示“全局界面透明度”：`0` 为不透明、`100` 为全透明。数值到物理表面 alpha 的换算只能在 `UiCorner.uiLayoutSurfaceAlpha()` 中发生；普通 UI、底栏玻璃外壳和液态玻璃内容均复用该入口，业务页面不得再自行反向计算。

### 异步 UI 与局部模糊

- 只处理真实浮层表面或明确声明的背景层，禁止扫描控件树猜测目标；找不到可靠目标时应暴露问题，不能扩大为宿主 Activity 全屏模糊或纯色兜底。
- 几何、着色、描边与模糊底图必须由同一表面模型和同一裁剪路径管理。每个浮层实例使用独立背景副本，不能混用可变 Drawable 或叠加互相冲突的形状背景。
- 取图必须在目标和宿主 attach、且几何连续两帧稳定后进行。`PixelCopy` 源矩形必须使用源 Window 坐标并严格相交裁剪；不能用强制最小 1 像素矩形掩盖坐标错误。
- 首次可见前完成背景安装。关闭、换目标、重新显示和尺寸变化要使旧回调失效并释放旧位图；样式更新只更新样式，不应取消同一目标仍有效的取图，回调安装时使用最新样式。
- 禁止 `RenderEffect` 作用于宿主 `decorView`，以及 `setBackgroundBlurRadius` / `FLAG_BLUR_BEHIND` 等整窗模糊路径。若要改变浮层外壳几何，先分离外壳、背景层、内容层并完成模拟器全路径验证。

### 半自动调试模式

当用户说“半自动模式”或“半自动调试模式”时，启用以下正式协作协议：

- 调试工具链由 Codex 持续运行和采集，包括显式目标的 ADB、uiautomator2、截图、logcat、Perfetto、Winscope 和 Frida；根据当前问题和假设选择需要的工具，不设固定升级层级。
- 界面操作由用户实时完成，包括点击、滑动、返回、打开菜单、启动播放和翻页。Codex 根据当前排障目标持续准备采集、观察和分析，不要求用户逐轮确认下一步。
- 这是一个连续调试回合，不得把每个操作拆成“先停下来询问、等待回复、再启动工具”的串行流程；工具采集、日志读取和证据对齐应在用户操作窗口内持续进行。
- Codex 可以在 commentary 中简短说明当前正在监听的目标和所需操作，但不得重复已经明确的操作指令，也不得因为等待用户操作而结束调试回合。
- 每次复现必须记录开始时间、用户操作窗口、结束时间和使用的设备；用户只操作唯一允许的雷电模拟器，Codex 只对雷电模拟器执行命令。
- 半自动模式不改变真机禁令、构建约束、诊断与动态验证规则或代码修改授权；它只规定“工具由 Codex 连续采集、界面由用户实时操作”的协作方式。

### 诊断与动态验证

- ADB、`uiautomator2`、截图、logcat、源码检查、运行时对象检查、Frida、Perfetto、Winscope 都可以参与找原因、收敛假设和验证现象；它们是并列的诊断工具，不存在“先用哪些工具、失败后才允许用 Frida”的层级关系。根据当前问题选择最直接、最有效的工具组合。
- Frida 本身既是找原因的工具，也是验证解决方案的工具：可以用 `trace`、运行时对象检查或最小范围 Hook 观察真实行为、试探变量、缩小原因范围；不需要等其他工具先得出结论后才能使用。
- 通过上述任意工具得到的原因解释和解决办法，在尚未把对应解决方案临时实施到运行中的应用并重新复现之前，只能标记为“有证据支持的原因假设”和“有证据支持的解决方案假设”，不得报告为“已确认根因”“解决方案已确认”“定位确认”或“修复已验证”。
- 当排查已经形成具体的“原因 + 可执行解决方案”后，默认必须进行动态回归确认：优先使用 Frida 在最小必要范围内临时实施该解决方案，替换目标方法实现、修改参数/字段/返回值或其他可控运行时状态，使应用行为真实发生相应变化，然后在相同复现条件下再次观察原问题并采集同类证据做 A/B 对比。
- 只有当临时方案实际命中运行路径，并且原问题按解决方案预期消失或发生对应变化，才能同时把原因和解决方案升级为“已确认”。若问题没有按预期变化，则原原因或解决方案至少有一项不成立或证据不足，必须继续调查，不能用源码推断、日志解释或静态证据替代动态回归结果。
- “找原因”“定位问题”“分析为什么”等请求，默认要求完成从诊断、形成原因与解决方案，到动态实施方案并回归确认的完整过程；不能在得到一个合理解释或看似正确的修改方案后自行结束。只有用户明确要求快速分析、简单检查、只做静态检查、禁止某类工具，或客观上无法进行等价动态干预时，才可以停留在假设阶段，并必须明确说明哪些结论尚未动态确认。
- Frida 运行时实验允许在最小必要范围内临时替换已有方法实现、修改参数、字段、返回值，并在确有必要时加载临时 DEX；允许反复 reload 脚本进行试探和 A/B 验证，禁止顺带改变无关业务状态。
- 资源/XML、Manifest、Gradle 配置、native 代码、签名/打包行为，以及新增/删除类、字段、方法或改变继承结构等无法由 Frida 可靠等价表达的问题，应使用其他能够直接改变对应变量的动态验证方法；当前环境无法完成等价动态实施时，最终结论只能标记为“有证据支持但未动态确认”，不得伪装成已确认原因或已确认解决方案。
- Frida 只能连接已按第 2 节规则确认的雷电模拟器目标，不固定假设 ADB 端口或 transport；目标不明确时停止。脚本错误、`Java is not defined`、目标类/重载解析失败、注入未实际命中或 A/B 复现条件不一致均视为动态验证失败。
- Perfetto、Winscope、Frida trace 等证据采集应围绕同一次复现：记录开始时间、操作、结束时间；将 UI 层级、时间线、Window/Surface 与调用证据对齐，明确观察结果、排除项、原因假设、解决方案假设和动态验证结果。
- 工具入口为 `tools\android-dev`，输出写入已忽略的 `test-records\android-dev`，不得提交 trace、截图、Frida 临时脚本、临时 DEX、临时二进制或虚拟环境。
- 动态实验结束后卸载注入脚本、恢复进程原行为并移除模拟器临时 server；运行时实验用于确认原因与解决方案的因果关系。若后续实施正式修复，最终实现必须落回源码，Frida 临时修改不得作为正式实现。

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
- 每个独立修改完成并通过代码审查后必须立即自动创建一个只包含该修改的 Git 提交，不等待用户另行要求，也不能把多个无关修改堆积后一次提交。正式 APK 编译只能在这些提交完成后开始；正式编译、安装和回归通过后，再提交版本基线与验证记录。发生回归时只允许从这些明确提交边界回退，禁止猜测性撤销工作区文件。

## 6. 当前交付基线

仅保留最近一次已交付版本，下一次覆盖安装必须在此基础上递增：

- `3.26.081821c` / `10690`，2026-08-18（UTC），`Future` 分支（代码提交 `71f8fe9`）。有声小说与普通小说融合（统一阅读入口、统一朗读引擎、文字与音频双向映射）、朗读页脚小面板与悬浮窗层级/避让统一、朗读封面旋转、墨水屏弹窗与主题模式切换统一、听书页文本渲染与缩进控制、通知权限与音频缓存进度修复等（详见 README 更新记录）。已通过正式后台 `:app:assembleAppC` 构建，退出码为 0；`aapt` 确认包名 `io.legado.app.c`、版本 `3.26.081821c` / `10690`、中文名 `阅读 C`、架构 `arm64-v8a`，`apksigner` 退出码为 0。已发布 GitHub Pre 版本 `v3.26.081821c`（prerelease，目标 `own` 分支 `71f8fe9`），源码已推送至 `origin/own`。本次未执行模拟器安装回归（用户未要求）。

每次交付后当场更新本节。历史发布信息应从 Git、GitHub Release 或提交记录查询，不在本文件累积。
