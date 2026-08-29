# 阅读 C / legadoC 项目总则

> 本文件是项目唯一的长期规则来源。它只保留可复用的原则、流程、环境约束和当前交付状态；一次性排障过程、界面细节、截图和临时记录不写入这里。
> 规则只写在 `AGENTS.md`。`docs` 下仅保留 `api.md` 与截图。



## 1. 核心工作原则

每次开始编程前，先重申并遵守以下原则：

> 解决根本问题，拒绝任何兜底；有问题，直接暴露。统一维护、统一修复，避免特殊代码不断膨胀。鼓励调查，鼓励详细日志和探针，鼓励联网搜索。

**本项目的默认工作模式永远是纯编码模式。** 用户未明确点名其他模式或验证动作时，不得启动编译、测试、运行、模拟器、动态调试、安装或回归；完成根因确认和必要源码修改后，立即提交并推送。

具体要求：

- 先定位事实、边界和根因，再修改代码；不能用静默回退、吞异常、默认值补丁或仅覆盖症状的分支掩盖问题。
- 相同问题应收敛到共同抽象、共同入口或共同数据源。新增特殊逻辑前，先证明现有统一路径无法正确表达该需求。
- 结论必须区分“已由证据确认”和“仍属假设”。复杂问题要补足日志、探针、截图或 trace，使后续排查可以复现。
- 任何失败都必须说明原因和下一步。构建异常在解决后记录现象、根因、修复方式和是否交付；只把能长期复用的结论保留在本文件，并及时修正或删除失效规则。
- 本项目是自用开发版：不得擅自添加面向用户的数据处理条数上限、章节上限、结果截断或其他无依据的“保护性”限制。需要控制运行资源时只能采用透明、统一的调度机制，不能丢弃用户请求、静默截断数据或把限制伪装成成功；任何确需限制的外部系统约束必须直接暴露并记录原因。

### 请求范围与执行边界

项目规则中已经明确规定的默认工作流属于用户未另行指定时的默认执行项，不视为擅自扩展；用户明确指令始终优先。语义不清时立即停下并询问，不自行猜测。

- 用户请求按字面含义执行，不推定隐藏含义，不擅自扩展任务范围，不以“顺便完善”“完整闭环”或其他理由追加用户未要求的工作。
- 用户要求修改某个文件、整理代码或执行其他具体动作时，只完成明确要求的事项。完成的独立修改按第 5 节 Git 规则自动分类提交；自动提交属于修改工作的固定收尾，不视为扩大任务范围，也不需要用户另行要求。
- **除全自动模式外**，只有用户明确要求正式编译、安装或正式模拟器回归时，才执行其明确要求的对应动作；不得因为修改了代码或配置文件就自行启动这些流程。**全自动模式是唯一例外：一旦用户明确启用，即自动获得为解决当前问题所需的编译、安装、运行、测试、回归和循环迭代权限，不需要逐项再次授权。**
- 用户未明确指定工作模式时，**一律按纯编码模式执行**：定位并确认根因后，把解决方案直接落实到源码，完成后立即按 Git 规则提交、推送，到此结束。纯编码模式不以验证为目标，不启动任何编译、构建、测试、运行、模拟器操作、动态调试、F 工具、安装或回归；也不得为了提前发现编译错误而做测试编译。只有用户明确点名其他模式或明确要求对应验证动作时，才切换到对应流程。

### 工作模式与执行层级

写代码和排障统一按下面的模式理解。模式决定“做到哪一步、是否进入验证、由谁操作、何时结束”，不改变根因优先、真机禁令、构建约束、代码修改授权和 Git 规则。

**默认模式就是纯编码模式。** 用户只描述问题、要求修复、要求改代码，或没有明确点名任何工作模式时，都按纯编码模式执行。只有用户明确说“低频率人工介入模式”“半自动模式 / 高频率人工介入模式”“全自动模式”，或明确要求正式编译、安装、正式回归时，才进入对应模式。任何模式都不得自行升级。

| 模式 | 触发说法 | AI / Codex 负责 | 人工负责 | 源码修改 | 编译 / 测试 / 动态验证 / 安装 / 回归 | 结束条件 |
|---|---|---|---|---|---|---|
| **纯编码模式** | **默认模式**；用户未明确指定其他模式时；或明确说“纯编码模式” | 静态定位并确认根因，把解决方案直接落实到源码 | 无 | **是** | **全部不做**；包括测试编译，也不得用编译找错 | 代码写完并按 Git 规则提交、推送后立即结束 |
| **低频率人工介入模式** | “低频率人工介入模式” | 排查、形成方案，并通过 F 工具把临时补丁注入当前模拟器进程 | 补丁注入后自行长期测试，并在下一轮反馈结果 | 默认**不是**；F 补丁不是源码修改 | **不自动编译、不自动测试、不自动安装、不自动回归** | F 补丁和状态悬浮窗保持有效后结束当前调试回合 |
| **半自动 / 高频率人工介入模式** | “半自动模式”“半自动调试模式”“高频率人工介入模式” | 持续运行 Frida、日志、截图、Perfetto、Winscope 等采集与分析 | 在同一连续回合中实时点击、滑动、返回、翻页、启动播放等 | 按用户授权；动态补丁可用于验证 | **不自动编译、不自动测试、不自动安装、不自动回归**；仅做当前运行进程上的动态诊断/验证 | 在同一回合持续复现、观察、分析，直到当前问题确认或用户停止 |
| **全自动模式** | 仅当用户明确说“全自动模式” | **完整接管问题闭环：自主复现 → 定位根因 → 修改源码 → 提交 → 自动编译 → 自动安装/运行 → 自动测试/回归 → 根据结果继续修改并重复整个循环，直到问题收敛**；按需使用 ADB、uiautomator2、截图、logcat、Frida、Perfetto、Winscope | 无 | **是** | **是；这是所有模式中唯一会主动自动编译、自动测试并持续循环迭代的模式** | 自动循环直到修复被验证通过，或证据证明当前方案不可行并形成明确结论 |
| **正式验证 / 交付** | 用户明确要求“正式编译”“安装”“正式回归”“完整验证闭环”或交付 | 按用户明确授权执行对应正式动作 | 仅在明确需要人工操作时参与 | 已完成的源码必须先提交 | **是，仅执行用户明确要求的部分** | 对应正式动作完成并记录结果；完整闭环为 `appC` 编译 → 模拟器安装 → 复现与回归 |

#### 纯编码模式

这是最轻量、也是默认的源码修改模式：**找到原因 → 把问题落实到代码 → 写完立即提交并推送 → 到此结束。** 纯编码模式的目标是完成代码修改，不承担任何验证工作。

- 先通过源码、调用链、配置、已有日志、仓库搜索和现有证据定位并确认根因；**不得为了找错、确认能否通过或提前发现小问题而启动编译、构建或测试**。
- 确认原因后只修改解决该问题所必需的代码；不顺手增加测试、动态探针、额外重构、文档或其他非必要工作。
- 写完后只做静态收尾检查，例如 `git diff` / `git status` 和必要的代码审查；这不属于运行验证。随后立即按第 5 节 Git 规则提交并自动推送，不等待用户另行要求。
- **禁止任何测试编译。** 不运行 Gradle 编译任务、构建任务、单元/仪器测试、Lint 等以验证修改为目的的任务；不启动 APP、模拟器、ADB、uiautomator2、Frida/F 工具、截图、logcat、Perfetto、Winscope；不安装、不回归。严禁新增、修改或删除 app/src/test/**、app/src/androidTest/**、.github/workflows/**，也严禁为测试或 CI 目的额外改造生产代码。
- 这样做是为了避免编译同时占用时间和大量内存，并避免多个并行编码请求与正式编译争抢构建资源。纯编码阶段允许把尚未暴露的编译小错误留到之后的正式编译阶段统一发现和处理，**不得为了提前消灭这些错误而把纯编码模式升级成验证模式**。
- 只有用户明确要求某项验证动作或明确切换到其他模式时，才执行该动作；否则代码提交、推送完成即结束。

#### 低频率人工介入模式

这是“AI 完成动态方案，人工之后慢慢测试”的模式。

- AI 先完成原因排查和动态方案验证所需准备，再通过 F 工具把补丁注入当前雷电模拟器中正在运行的阅读 C 进程。
- 补丁注入成功后，不设置自动失效时间，并同时显示持续可见、足够醒目的状态悬浮窗，明确标记“Frida 补丁已注入并生效”。
- 补丁与悬浮窗保持同一生命周期；补丁仍有效时，悬浮窗不得自行消失。
- 注入完成后结束当前调试回合，不继续要求人工实时配合；之后由人工自行测试，并在下一轮对话反馈结果。
- F 工具补丁属于运行时临时验证，不等于把补丁写入源码。

#### 半自动 / 高频率人工介入模式

“半自动模式”“半自动调试模式”和“高频率人工介入模式”视为同一套协作协议：**AI 持续采集和分析，人工实时操作界面。**

- 调试工具链由 Codex 持续运行和采集，包括显式目标的 ADB、uiautomator2、截图、logcat、Perfetto、Winscope 和 Frida；根据当前问题和假设选择需要的工具，不设固定升级层级。
- 界面操作由用户实时完成，包括点击、滑动、返回、打开菜单、启动播放和翻页。Codex 根据当前排障目标持续准备采集、观察和分析，不要求用户逐轮确认下一步。
- 这是一个连续调试回合，不得把每个操作拆成“先停下来询问、等待回复、再启动工具”的串行流程；工具采集、日志读取和证据对齐应在用户操作窗口内持续进行。
- Codex 可以在 commentary 中简短说明当前正在监听的目标和所需操作，但不得重复已经明确的操作指令，也不得因为等待用户操作而结束调试回合。
- 人工每次操作后，AI 立即继续观察和分析，不把结果留到下一轮再处理。
- 每次复现必须记录开始时间、用户操作窗口、结束时间和使用的设备；用户只操作唯一允许的雷电模拟器，Codex 只对雷电模拟器执行命令。
- 本模式不改变真机禁令、构建约束、诊断与动态验证规则或代码修改授权。

#### 全自动模式

这是用户**明确点名后才启用**的最高强度工作模式，也是**所有模式中唯一会自动编译、自动测试、自动安装/运行并持续循环迭代的模式**。它不是“只做动态诊断”，而是由 AI 完整接管从定位到最终验证的整个工程闭环。

- 启用后，AI 不再等待用户逐项授权编译、安装、测试或回归；这些动作属于全自动模式本身的固定权限。
- 标准循环是：**自主复现问题 → 采集证据并定位根因 → 修改源码 → 按 Git 规则提交、推送 → 自动正式编译 `appC` → 安装到设备名包含 `emulator` 的模拟器 → 自动运行和测试/回归 → 分析结果 → 若仍有问题则继续定位、改代码、提交、再编译、再安装、再测试**，持续迭代直到问题被验证解决。
- AI 自主选择 ADB、uiautomator2、截图、logcat、Frida、Perfetto、Winscope 等工具；Frida 既可用于定位，也可用于在正式编译前快速验证假设，但不能替代最终自动编译后的实际回归。
- 编译或测试暴露出的源码错误、资源错误、运行错误或新回归，直接进入下一轮修复，不停下来等用户确认；这正是全自动模式与其他模式的核心区别。
- 每一轮正式编译、安装、产物校验和回归仍必须遵守第 2、3 节的设备、版本、构建和产物约束；“自动”只表示无需逐项人工授权，不表示可以绕过正式构建规则。
- 结束条件是：当前问题已在自动编译后的真实 APK 上完成自动复现与回归并确认解决；或者经过自动迭代后有充分证据证明当前方向不可行，并形成明确原因与下一步。

#### 正式验证 / 交付

正式验证不是排障模式的自动下一步，而是独立授权层级。

- 用户只要求“正式编译”“安装”或“正式回归”其中一项时，只执行该项，不自行补齐其他动作。
- 用户明确要求“完整验证闭环”时，执行：正式编译 `appC` APK → 安装到设备名包含 `emulator` 的模拟器 → 复现并回归验证。
- 所有正式动作继续遵守第 2、3 节的设备、构建、版本和产物约束。

#### F 工具术语与修饰指令

- **F 工具就是 Frida。** 用户说“F 工具”时按 Frida 理解。
- “用 F 工具推上去永久有效”表示：把补丁通过 Frida 注入到当前模拟器中正在运行的阅读 C APP，并且不设置失效时间，用于持续验证方案可靠性；**不是把补丁写进源码**。
- 任何需要持续有效的 F 工具补丁，注入成功后必须同时显示持续可见、足够醒目的悬浮窗，明确标记“Frida 补丁已注入并生效”；补丁仍有效时悬浮窗不得自行消失。

## 2. 设备与测试边界

### 真机禁令

- 绝对禁止对用户真机执行任何操作，包括所有 `adb` 子命令、截图、点击、安装、卸载、push/pull 和 shell。
- 真机问题只能依据用户描述、代码和用户提供的日志排查；高级调试工具不是例外。

### 雷电模拟器与 ADB 身份判定

- APK 安装、运行和调试只使用雷电模拟器（LDPlayer），路径为 `F:\leidian\LDPlayer14\dnplayer.exe`。未启动时可尝试启动；失败则请用户手动打开。
- 真实小说优先用于阅读功能验证。`C:\Users\user\Documents\leidian14\Pictures` 与模拟器 Pictures 目录互通，可作为导入素材。
- ADB 设备身份只按设备名判断：设备名包含 `emulator` 的，才认定为模拟器；设备名不包含 `emulator` 的，一律不认定为模拟器。只认这一条规则。

### 验证闭环

**规则裁决：ADB 设备是否为模拟器只按设备名是否包含 `emulator` 判定；不包含 `emulator` 的设备一律不是模拟器。不得以任何其他字段或外部校验替代、补充或推翻这条规则。**

本节在两种情况下适用：**用户明确要求对应正式验证动作，或用户明确启用全自动模式。** 非全自动模式下，用户明确要求完整验证闭环时，按以下流程执行：正式编译 `appC` APK -> 安装到设备名包含 `emulator` 的模拟器 -> 复现并回归验证；用户只要求其中一项时，只执行该项，不自行补充其余步骤。**全自动模式下则由 AI 自主重复该闭环，并根据每轮结果继续修改和迭代，无需用户逐项再次授权。**

- 模拟器不可用时不得改用真机。
- 崩溃或行为异常时，先按当前模式完成原因定位。**非全自动模式**只有用户已明确要求正式验证闭环时，才在正式修复后继续正式编译和回归；**全自动模式**则自动进入编译、安装、测试和回归，并在失败时继续下一轮修改。
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
- 编译前直接使用第 6 节的最近交付基线，不查模拟器已安装版本。确认新版本后，只删除 `app\build\outputs\apk\app\c` 中对应的旧 APK，绝不删除宽泛目录或源码。

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
- 2026-08-19：`assembleAppC` 增量 daemon 编译在 `compileAppCJavaWithJavac` 阶段报“Gradle build daemon disappeared unexpectedly”，`hs_err_pid*.log` 显示 JVM 原生内存不足（native OOM，malloc 失败）。根因是系统物理内存/交换空间不足，不是源码错误。清理残留 Gradle/Kotlin/Java 进程后，按冷编译参数 `--no-daemon --max-workers=1 -Dkotlin.incremental=false -Dksp.incremental=false -Dkotlin.compiler.execution.strategy=in-process` 全量重编译成功。本机内存压力下再次构建应直接采用该冷编译参数，不要盲目重跑 daemon 增量编译。
- 2026-08-22：另一会话在同一工作区运行 Gradle 时并行启动 `assembleAppC`，`processAppCResources` 报 `Couldn't delete ...\R.jar` 失败（跨进程文件锁，非源码错误）。同一检出目录同时只允许一个构建；正式编译前必须确认无其他活跃 `GradleWrapperMain` / 临时编译脚本，发现冲突先协调等待，不得擅自杀死不属于本次构建的进程。另外：经 PowerShell 管道截断（如 `Select-Object -First N`）读取 `.bat` 包装工具输出会污染 `$LASTEXITCODE`，校验退出码必须完整执行后读取。
- 2026-08-24：Kotlin 字符串模板 `$total章` 报 `Unresolved reference 'total章'`——Kotlin 标识符允许 Unicode 字母，模板变量后紧跟 CJK 字符会被并入标识符解析；非 ASCII 后缀前必须用 `${var}` 显式终止引用。
- 2026-08-24：用 PowerShell 字符串拼接生成后台构建 `.bat` 时，含拼接表达式的行被拆成多行、重定向路径断裂，进程秒退且无任何日志文件。此类"启动器损坏"一律按未启动处理，不得等待或误判为编译卡死；生成脚本改用纯字面量 here-string（长路径经 `%VAR%` 间接引用），且必须先回读校验行数与内容再 `Start-Process` 启动。
- 2026-08-24：后台构建 `.bat` 用 `echo %EXIT_CODE%> "file"` 记录退出码，`%EXIT_CODE%` 为纯数字（如 0）时该行被 cmd 解析为句柄 `0>` 重定向，退出码文件为 0 字节空文件（构建本身正常，成功以 out.log 的 BUILD SUCCESSFUL 与空 err.log 为准，此问题不触发重编译）。记录退出码必须把重定向写在行首：`> "file" echo %EXIT_CODE%`。

### 双构建路线（自有 / 开源）

主代码只经 `app/src/main/java/io/legado/app/plugin` 的空接口与注册表（`ReadAloudEngines` / `TtsVoiceDirectories` / 各 flavor 的 `AppPlugins.init`）接触专有功能；插件缺失时主代码正常运行：引擎列表不渲染该行、路由到未内置引擎 id 明示回退系统 TTS、AI 选角在发音人目录缺失时自动降级。

- 自有构建（阅读C）= `assembleAppC`：flavor `app` 自动并入 `app/src/app` 源集——百度引擎（`help/bdtts`、`BdReadAloudService`、`BdEngineManageActivity`、`com.baidu` SDK、`jniLibs/*.so`、自身 `app/src/app/AndroidManifest.xml` 与 `appImplementation(libs.snakeyaml)`）整体在包内，由自有 `AppPlugins` 注册为插件。
- 开源构建 = `assembleOssRelease`：flavor `oss` 不并入 `app/src/app`，专有插件源码/so/组件声明/snakeyaml 完全不参与编译与打包（产物内不存在这些代码）；包名 `io.legado.app.refgd`、应用名"阅读"（`src/oss/res` 覆盖，繁中为"閱讀"）、版本 `3.26.MMddHH` 无后缀。
- 新增专有功能一律放 `app/src/app`（或另开 flavor 专属源集）并在自有 `AppPlugins` 注册；开源构建自动剥离。
- 若公开发布整个仓库源码而非仅 APK，`app/src/app` 下的专有代码会随源码泄露，需要导出过滤（只发布 APK 不受影响）。

编译选择规则（默认自用，按用户点名才变）：

- 用户未指明构建路线时，"编译/正式编译/交付"一律指自用构建 `assembleAppC`（阅读C），完全沿用"不可变交付约束"与本节的版本、产物规则；不得自行切换成开源构建。
- 仅当用户明确点名"开源编译/发布编译/oss 编译"时，才执行 `assembleOssRelease`：同样传 `-PVERSION_CODE`/`-PVERSION_NAME`（版本名不带 `c`，oss flavor 无后缀），产物在 `app\build\outputs\apk\oss\release\`；验证用同一套 `aapt`/`apksigner` 流程，但身份预期不同——包名 `io.legado.app.refgd`、中文名"阅读"（繁中"閱讀"）、版本名无后缀。不得把 ossRelease 当作阅读C 的交付物，也不得用 appC 冒充开源发布包。
- 用户明确要求"双编译"时，两个构建都执行：先自用 `assembleAppC`，再开源 `assembleOssRelease`，各自完整走一遍版本传参与产物验证；两包包名不同，同版本号互不影响覆盖安装。

开源源码发布（历史清洗镜像）：

- 远程 `origin`（CCSSNE/legadoC）是公开仓库（默认分支 `own`）。`origin/own` = 本地完整历史剥离专有路径后的清洗镜像；本地 `own` = 完整私有历史，同步推送私有备份仓 `private`（CCSSNE/legadoC-private，已验证 `private:true`）。
- 专有/自用代码 100% 集中在剥离清单所列路径（现行 `app/src/app` + 五个迁移前旧路径），主代码仅剩注释与日志关键词文本；`gradle.properties` 无密钥（签名全靠构建时传参）。`AGENTS.md`、`docs`、`tools` 不在剥离范围，随历史公开（8 月中旬起已公开，用户知情）；assets 的"百度汉语"词典与默认 HTTP TTS 源属上游 legado 公开内容，照常保留。
- **严禁把本地 `own` 直接 `git push` 到 `origin/own`**：两边历史不同，非快进必被拒（这是防泄露保护，不得绕过）；强推会把专有历史重新公开。（私有备份仓 `private` 收的就是完整历史，直接 `git push private own` 快进属正常操作，不在禁止之列。）
- 发布统一走仓库根 `publish-oss-source.ps1`：临时克隆 → `git filter-repo --invert-paths` 按剥离清单改写全历史 → 全历史校验剥离路径零命中 → 末尾注入确定性"空壳插件引导"提交（固定时间戳，保证公开树 app/oss 两 flavor 均可编译）→ 从临时克隆 `--force` 推 `refs/heads/own` → 把本地完整历史快进推送到私有备份仓 `private`（CCSSNE/legadoC-private）。清洗是确定性的：未受污染的旧提交哈希不变，后续同步通常为快进，仅本地历史重排时才真正强推。
- 已有 fork 与 GitHub 服务端缓存可能仍留存清洗前的旧对象；需要彻底清除时联系 GitHub Support（remove sensitive data）。
- 剥离清单改动必须同步脚本头部注释与本节；新增专有功能若不放进剥离清单所列路径（新专有功能一律放 `app/src/app`），必须先更新剥离清单再发布。

### 产物验证

```powershell
$apk = 'D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_<version>.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk
```

交付前确认：包名 `io.legado.app.c`、版本号递增、中文名 `阅读 C`、`arm64-v8a`、产物来自 `appC`，且 `apksigner` 退出码为 0。部分 `META-INF` 条目未受签名保护的提示可接受。

## 4. 工程质量规则

> UI 设计相关规则（无头弹窗策略、UI 内核与浮层规范、异步 UI 与局部模糊）已单独维护在 `docs/ui-design-spec.md`。

### 朗读状态所有权契约

朗读系统是“两个原语 + 一条跟随规则 + 派生事实 + 两个开关”，不存在存储的跟随/脱钩状态：

**顶层状态（每个状态只有一个写者类别；任何新代码不得增加位置类状态副本或旁路写点）：**

| 状态 | 唯一写者 | 其余模块 |
|---|---|---|
| `ReadAloud.aloudPosition`（朗读位置唯一真相） | 朗读引擎，经 `publishAloudPosition` 单点发布（带 generation 防乱序） | 只读 |
| 显示进度（`ReadBook.durChapterPos` / 物理显示页） | 用户操作、数据同步、跟随规则（`shouldFollowAloudAdvance` 判定通过后由 ReadBookActivity 观察者单点写）、`backToAloudProgress`（回原进度） | 朗读引擎零写权限（`postReadAloudTextPosition` 只发布位置） |

**两个原语：**

- 原语A 双击换段 `setAloudStart(position)`：只写“读哪里”，不联动任何显示状态。所有起点设置归一到它：双击段落（真段首）、从本页读（本页第一段）、强制追页翻页（新页第一段）、选择朗读。
- 原语B 回原进度 `backToAloudProgress()`：把“看哪里”对齐“读哪里”。自动触发仅一处——用户上一章/下一章（命令层 Intent 携带 `syncView=true`，引擎跳章后显示章节一起切）；其余一切事件默认不触发。

**跟随规则（纯判定，无存储）`shouldFollowAloudAdvance(prev, current)`：** 显示物理页 == 朗读出发页（prev 所在页）且位置前进时才写显示进度并翻页；其余（用户翻到别处、回退型起点补读期）一律不动。显示永不被朗读事件拽向后退，“该跳才跳/回退不拽页”由这一条单调性规则全覆盖，不需要地板/闩。

**派生事实（每帧现算，禁止存储）：**

- 显示与朗读脱节 `isViewBehindAloud()`：显示页 != 朗读位置所在页 → PAGE_ACTION 面板（回原进度/从本页读）出现，对齐后自动消失。
- 朗读红字高亮 = `TextLine.isReadAloud` 绘制期现算：本行段号 == 引擎同款 `getParagraphNum` 判定的朗读段号且 `isPlay()`。禁止把高亮写进 TextPage/TextLine 存储字段；显示变化靠换页全量重绘覆盖，朗读/播放状态变化只经 `ReadView.invalidateReadAloudHighlight()` 单一失效入口清绘制缓存。
- `durPageIndex` 是 `durChapterPos` 的推导值，不得当作物理显示页使用；物理显示页只认 `ReadView.curPage`。

**两个开关（原 `readAloudByPage` 按页朗读已拆分删除，不做老配置迁移）：**

- `forcePageFollow` 强制追页：ON 时翻页被翻译成双击换段（新页第一段），视角永远在朗读页；OFF 时翻页不联动，脱节由派生条件呈现。
- `pageSplit` 页间分段：ON 时跨页的段从页边界裂成真正的两个朗读单元（边界绝对准，段间有停顿）；OFF 时整段连读，读到跨页处自然翻页。预测换页（按文字量比例预估翻页时刻）是未来高级功能，挂载点约定见 `BaseReadAloudService.pageSplit` 字段注释：预测只允许影响位置事件的发布时机，不得新增显示写点。
- `readAloudPageStartAtParagraph` 页首按段保留：只影响“本页第一段”的解析方式，其回退行为由跟随规则自然兜住。

流程收口：所有改变朗读位置的操作只经 `setAloudStart(position)`；引擎内部光标（`contentList/nowSpeak/readAloudNumber/textChapter/pageIndex/currentChapterIndex/paragraphStartPos`）只能由引擎推进方法读写，对外仅 `publishAloudPosition` / `publishParagraphProgress` 两个出口；引擎跨章推进时“显示章节是否跟随”同为派生判定（`syncView || 显示章==朗读章`），视角在别处时走 `switchReadAloudChapterKeepingView` 只切朗读不动显示。

朗读诊断日志约定：全部走 `AppLog.putDebug`（需开启"记录日志"设置）、统一 `[朗读]` 前缀、归属 `LogModule.READ_ALOUD`（ReadBookActivity/ReadBook/ReadView 等按类名会被误归阅读模块的调用点必须显式传 `module`）；覆盖点为操作层（双击换段/从本页读/手动翻页/回原进度）、位置发布（publish/confirm/cancel/clear）、跟随决策（跟随写显示/不跟随/忽略）、引擎（章节准备/起点偏移/视角保持切章）、状态事件与高亮失效。绘制路径（TextLine/TextPage）禁止打点。用户报 bug 时附上普通日志（勾选朗读模块）即可按链路定位。

### 设置默认值

每个设置的界面默认值与实际读取默认值必须一致：

- 界面默认值在 `app\src\main\res\xml\pref_config_*.xml` 的 `android:defaultValue`。
- 实际默认值在 `AppConfig.kt` 及各调用点的 `getPrefBoolean`、`getPrefInt`、`getPrefString`。`getPrefBoolean(key)` 不带默认参数时默认是 `false`。
- 修改任意设置默认值时，全库搜索该 key 的所有读取点，逐一核对类型和值；界面显示与实际行为不一致属于缺陷，不能接受“默认分支行为等价”作为理由。
- 背景图这类文件型默认值不能写成某台设备的绝对路径。必须把素材随 APK 提供，并由统一主题初始化在 `applyDayNightInit()` 前复制到应用私有目录，再为尚未配置的日间/夜间 key 写入该稳定路径。`backgroundImage` / `backgroundImageNight` 缺失表示从未配置；空字符串表示用户明确移除背景，后续启动不得覆盖。
- `uiLayoutAlpha` 的值表示“全局界面透明度”：`0` 为不透明、`100` 为全透明。数值到物理表面 alpha 的换算只能在 `UiCorner.uiLayoutSurfaceAlpha()` 中发生；普通 UI、底栏玻璃外壳和液态玻璃内容均复用该入口，业务页面不得再自行反向计算。

### 诊断与动态验证 

工作模式的定义、人工介入频率和 F 工具持续补丁规则统一见第 1 节“工作模式与执行层级”。本节只定义所有调试模式共同使用的诊断能力。

- ADB、`uiautomator2`、截图、logcat、源码检查、运行时对象检查、Frida、Perfetto、Winscope 都可以参与找原因、收敛假设和验证现象；它们是并列的诊断工具，需要什么，用什么。
- Frida 本身既是找原因的工具，也是验证解决方案的工具：可以用 `trace`、运行时对象检查或 Hook 观察真实行为、试探变量、缩小原因范围；不需要等其他工具先得出结论后才能使用。
- 动态验证只能证明当前运行环境中的假设与方案；不能被可靠等价注入的资源/XML、Manifest、Gradle、native 或类结构改动，必须明确标记为“Frida 未完整验证”，不得冒充正式 APK 回归。

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

- git 提交必须为中文说明。 如果遇到历史的提交是英文说明，也会顺手改为中文，并且强推到线上。

- 提交前检查 `git status`、`git diff`、`git log`。只暂存本次需要的文件，不提交 APK、构建日志、trace 或临时文件。
- 提交信息简洁且准确，遵循现有仓库风格。
- 项目不使用远程 CI：`.github/workflows` 下全部工作流已于 2026-08-22 移除（unit-test 因 `gradlew` 缺少可执行位从未通过；publish-release-to-telegram 为上游继承、secrets 未配置的死配置），远程 Actions 无存量运行负担。单元测试与编译检查一律在本地执行；不得重新引入或恢复远程 CI 工作流。
- 每个独立修改完成并通过代码审查后必须立即自动创建一个只包含该修改的 Git 提交，不等待用户另行要求，也不能把多个无关修改堆积后一次提交。正式 APK 编译只能在这些提交完成后开始；正式编译、安装和回归通过后，再提交版本基线与验证记录。发生回归时只允许从这些明确提交边界回退，禁止猜测性撤销工作区文件。
- **自动推送（顺手就推）**：本地仓库一旦有新提交（含基线记录提交），立即运行仓库根 `publish-oss-source.ps1` 把清洗镜像同步到 `origin/own`，不等待用户另行要求；自己写的代码自己推。禁止对 `origin/own` 直接 `git push`（own 与 origin/own 历史不同：本地完整私有，远程为剥离专有路径的清洗镜像；详见第 3 节"开源源码发布"）。此规则只涵盖代码提交的推送，不含 GitHub Release 的创建/上传——Release 发布仍必须等用户明确指示，不得自动进行。同步后以发布脚本输出的 `origin/own = <sha>` 确认远程已更新。

## 6. 当前交付基线

仅保留最近一次已交付版本，下一次覆盖安装必须在此基础上递增：

- `3.26.082923c` / `10766`，2026-08-29（UTC 23 时），`own` 主线（编译锚点 `own @ 45633bf4`；基于基线 `10765` 后新增提交 `725684a5`（新增朗读引擎插件SPI注册表，App启动按构建入口分flavor注册专有插件）、`2742f975`（构建双路线：新增开源oss flavor，百度TTS源码与动态库迁入app/src/app专属源集，仅自有构建合并打包）、`2895c40d`（朗读引擎选型与AI选角改走插件注册表：主代码不再引用百度引擎，缺失时明示回退系统TTS）、`7b78a9c5`（AGENTS.md记录双构建路线与源码泄露注意点）、`d75f85eb`（朗读面板当前引擎按钮文本不换行：标签与引擎名各占一行并统一单行省略）、`c34c2e03`（AGENTS.md明确编译选择规则：默认自用appC，点名开源/双编译才编oss）、`ca85e963`（新增开源源码发布脚本与上传屏蔽规则）、`951086b8`（修复默认引擎与百度引擎打架死路）、`38ed8a28`（源码发布改为历史清洗镜像：filter-repo剥离专有路径后强推origin/own）、`22582386`（引擎选择状态修正：百度引擎未导入语音包时不可选，摘要统一走selectedEngineLabel解析）、`f8aeb6e6`（修复百度TTS语速滑杆无效：native语速倍率换算错误致滑杆全压最低档，音量同源错算一并修正）、`6d32035f`（百度TTS改流式直传播放：合成回调PCM直写AudioTrack，删除落盘链路，保留流式特性）、`094159a7`（发布流程并入私有备份仓自动推送）、`8e75b412`（本次编译修复：引擎类型判定编译失败）、`45633bf4`（发布流程并入公开备份fork自动同步））。首轮编译失败：`compileAppCKotlin` 报 `ReadAloud.kt:124` `Argument type mismatch: actual type is 'Class<CapturedType(*)>?', but 'Class<*>' was expected`——`BaseReadAloudService.runningClass` 声明为 `Class<*>?`，直接抛入 `byServiceClass(Class<*>)` 时 Kotlin 在 `when` 的 else 分支无法消除可空 star projection 捕获类型；改经 `running?.let { ReadAloudEngines.byServiceClass(it) }` 传非空 `Class<*>`，判定结果不变（提交 `8e75b412`）。编译期间检测到并发新提交 `45633bf4`（仅改 publish-oss-source.ps1 发布脚本，与 APK 内容无关），按"编译期间出现新提交须基于新分支头重编译"重编译：app 源码未变，Gradle 判定 75 个任务全部 UP-TO-DATE，退出码 0。最终一轮：BUILD SUCCESSFUL in 5m（75 actionable tasks: 10 executed, 65 up-to-date），err.log 为空，退出码 0。`aapt` 确认包名 `io.legado.app.c`、版本 `3.26.082923c` / `10766`、中文名 `阅读 C`（zh）、架构 `arm64-v8a`、compileSdk 36；`apksigner` 完整执行退出码 0（Signer #1 CN=Android Debug，SHA-256 `70cb88ca...`）。产物 `legado_app_3.26.082923_10766.apk`（Gradle 自动命名）收录于 `app\build\outputs\apk\app\c` 并备份至 `D:\AI\audio\build-tmp`，旧 `10765` APK 已由 Gradle 重建产物目录自动清除。构建后 `gradlew --stop`、无残留构建进程；未安装到模拟器、未做正式回归。

每次交付后当场更新本节。历史发布信息应从 Git、GitHub Release 或提交记录查询，不在本文件累积。
