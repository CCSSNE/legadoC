# 项目总规则（阅读 C / legadoC）

> 本文件是项目唯一总规则。所有开发、编译、测试、发布行为都必须遵守。
> 用户明确要求：规则只写在 AGENTS.md，docs 下除 api.md 与截图外的文档已删除。

## 一、真机禁令（最高优先级，违者严重）

1. **绝对禁止对用户的真机执行任何操作**：包括 adb install/uninstall、am force-stop、logcat -c、push/pull、shell、截图、点击等一切 adb 操作。
2. 需要安装/测试 APK 时，只允许操作**雷电模拟器**；执行任何 adb 命令前，必须先确认目标设备是模拟器（序列号如 `emulator-5554` 或雷电的 `127.0.0.1:5555`），不确定时一律不做，禁止裸 `adb install` 命中未知设备。
3. 真机上的任何异常排查都只通过用户描述、代码分析和日志文件进行，不主动连接真机。
4. 每次代码改动后的功能测试：重新编译 → 安装到雷电模拟器 → 在模拟器中复现/验证。模拟器未就绪时不得退而求其次在真机上测试。

## 二、测试（模拟器）

1. 本机固定使用**雷电模拟器（LDPlayer）**，路径：`F:\leidian\LDPlayer14\dnplayer.exe`。未启动时先自行尝试启动；失败则请用户手动打开。
2. 安装命令必须限定模拟器序列号（如 `-s emulator-5554` 或 `-s 127.0.0.1:5555`），不允许裸 `adb install`。
3. 测试过程中遇到崩溃：收集崩溃日志 → 定位代码问题 → 修复 → 重新编译 → 再到模拟器回归验证。
4. 测试素材：尽量拿真实的小说测试；`C:\Users\user\Documents\leidian14\Pictures` 与雷电模拟器 Pictures 文件夹互通，里面放了小说可直接导入。

## 三、编译与验证

1. 验证任何代码改动，**一律直接编译正式版**：`.\gradlew.bat ':app:assembleAppC'`（带 `-Pabi=arm64-v8a`、递增的 `-PVERSION_CODE` / `-PVERSION_NAME`），产物必须来自 `app\build\outputs\apk\app\c`。
2. **禁止**只跑 `compileAppCKotlin`、`processAppCResources` 等中间任务来"验证"改动——它们不产出 APK，跑完也没有交付物。
3. 交付给用户安装时只编译 `appC` 变体，禁止 debug 编译；禁止把 `app\build\outputs\apk\app\debug` 的 `.debug` 包当阅读 C 包交付。
4. 覆盖安装给用户测试时，必须显式传入 `VERSION_CODE` 和 `VERSION_NAME`，不依赖默认版本号逻辑（默认逻辑受 git 提交数和编译时间影响，容易打出不能覆盖升级的包）。

## 四、版本号规则

1. 每次编译安装包前，先确认已安装版本的 `versionCode`（能连模拟器时用 `adb -s <模拟器> shell dumpsys package io.legado.app.c` 查；不能连时用第 7 条"最近一次已交付"基线）。
2. 新包 `VERSION_CODE` 必须大于上一交付；按已交付线每次 `+1`。不能只看当前输出目录，更不能复用旧值。
3. `VERSION_NAME` 格式是 `3.26.MMddHH`（默认按 GMT+8 编译时刻生成），并且每次交付必须大于上一包；也可以沿用/顺延上一包名加一。
4. 删除旧包本身不提供版本号：版本号必须在删除前从模拟器或第 7 条基线确认好；第 7 条是"最近一次已交付"的唯一持久记录，每次交付后必须当场更新，否则下一包会复用旧值、破坏单调递增。
5. 编译之前必须删除旧安装包，禁止把老包改名冒充新包。

## 五、编译命令（本机环境）

- JDK 17: `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`
- Android SDK: `D:\AI\audio\android-sdk`
- Gradle user home: `D:\AI\audio\android-gradle-user-home`
- Gradle wrapper: `8.14.4`，compileSdk: `36`
- 已恢复 Kotlin/KSP 增量编译和 Gradle daemon、多 worker；`GRADLE_USER_HOME` 在 D 盘与项目同盘，无跨盘增量缓存问题；内存由 `-Xmx6g` 兜底。首次编译全量，第二次起增量。

```powershell
$OutputEncoding=[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME='D:\AI\audio\android-sdk'
$env:ANDROID_SDK_ROOT='D:\AI\audio\android-sdk'
$env:GRADLE_USER_HOME='D:\AI\audio\android-gradle-user-home'
$env:Path = @(
  "$env:JAVA_HOME\bin",
  "$env:ANDROID_HOME\cmdline-tools\latest\bin",
  "$env:ANDROID_HOME\platform-tools"
) + ($env:Path -split ';') -join ';'

$versionCode=<新版本号>
$versionName='3.26.<MMddHH>'
.\gradlew.bat ':app:assembleAppC' '-Pabi=arm64-v8a' "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" --console=plain --warning-mode=summary
```

- 后台启动编译并轮询日志可行：用 `Start-Process` 拉起 `gradlew.bat`（或写 .bat 脚本），标准输出和错误输出分别重定向到两个文件，编译结束后退出码写文件，轮询该文件判断完成；不要用固定短超时死等。
- 注意：cmd.exe 内联重定向（`/c` 带 `> file 2> file`）偶尔会失效导致编译根本没启动、日志为空——此时改用 .bat 脚本文件方式最可靠。

## 六、长命令实时监控（强制）

1. 任何可能超过 30 秒的命令（编译、打包、长脚本等），**必须加实时监控**，不允许干等或只启动不管。
2. **每 30 秒探测一次**：检查进程是否存活、CPU 是否在增长、日志/产物是否有更新。
   - 进程 CPU 持续增长、日志在写 = 正常运行，继续等待。
   - CPU 停滞且无日志更新 = 疑似卡死，立即终止并报告，不干等。
3. **命令不能超过 30 秒超时**：单次等待/探测间隔不超过 30 秒；超时未完成必须主动检查状态并向用户说明，而不是无限死等。
4. 编译结束（成功或失败）后：执行 `.\gradlew.bat --stop` 并清理残留 Gradle/Kotlin daemon 进程（按 PID `Stop-Process -Force`），不留待机进程占内存。
5. 任何异常（BUILD FAILED、OOM、进程异常退出、日志停滞）必须明确告知用户原因和下一步，不允许静默忽略。

## 七、已交付版本基线（新 → 旧）

- `3.26.081505c` / `10594`：2026-08-14 编译交付（PopupWindow 局部模糊优先使用 `mBackgroundView` 背景外壳，预先隐藏未布局外壳后再安装局部模糊，修复阅读页更多菜单范围偏移与首帧闪变；保留阅读主菜单和普通弹窗的局部模糊准备态）。
- `3.26.081424c` / `10592`：2026-08-14 编译交付（自模拟器实装基线 `3.26.081421c` / `10591` 覆盖升级，10586–10591 区间此前未录入基线；代码基线 c59e012，含阅读页弹窗局部模糊与菜单抽动修复；模拟器安装、启动验证通过）。
- `3.26.081423c` / `10585`：2026-08-14 编译交付（补齐主题/底栏管理日夜条、编辑条、添加条与缓存批量条的透明悬浮块组；移除其它设置分类横杠并让文件选择路径区透明；所有标准弹窗接入日间/夜间底色、透明度与模糊度，补齐来源选择、行式选择、标签展开和通用菜单等自定义弹窗；模拟器完成全覆盖回归）。
- `3.26.081419c` / `10581`：2026-08-14 编译交付（其它设置语言下方分隔线移除；文件管理顶部路径条改为纯透明；新增弹窗透明度与标准模糊度设置，日间/夜间弹窗分别复用现有灰色底色；缓存管理选中态统一为标准柔和高亮）。
- `3.26.081418c` / `10580`：2026-08-14 编译交付（缓存管理分组条与关于界面顶部说明条接入标准玻璃透明；普通主页壁纸不再受全局透明度影响；书页背景透明度仅控制原图并支持实时刷新；修复辅助玻璃层递归渲染崩溃）。
- `3.26.081417c` / `10579`：2026-08-14 编译交付（书架分组条与底部主页栏统一日间/夜间玻璃透明规则；设置、阅读统计、搜索条、分隔线和书架操作条跟随全局透明度；主页背景图纳入全局透明度，阅读页背景图新增跟随透明开关；夜间弹窗保持不透明并复用现有灰黑表面色；修复辅助玻璃层递归渲染崩溃）。
- `3.26.081413c` / `10575`：2026-08-14 编译交付（阅读设置新增整页书签样式"尖角朝上（底部凹口）"为默认；分组编辑弹窗新增"默认显示"勾选（唯一）：书架启动/切主分类优先显示勾选分组（与排序无关），未勾选时回退"全部"（若未关闭），否则第一个；DB 升级至 100）。
- `3.26.081412c` / `10574`：2026-08-14 编译交付（阅读设置新增整页书签样式：尖角朝下、尖角朝上（底部凹口）两种，默认保持尖角朝下；翻页模式、滚动模式和下拉动效同步生效）。
- `3.26.081409c` / `10571`：2026-08-14 编译交付（选中文本弹窗的书签/段落书签恒定为"添加"（去掉命中旧书签即编辑）；目录书签页打开自动定位到当前章节第一个书签（无则取章节距离最近，列表/网格都生效）；单击普通书签除切换备注气泡外，额外弹出临时"编辑当前标签"悬浮窗（重叠命中规则=文本更短优先、相同创建更晚优先，点其他区域消失）；整页书签与普通书签彻底分开：阅读页单击不劫持/不弹气泡/不弹编辑、目录列表带"整页"标记、多选批量编辑不受限）。
- `3.26.081408c` / `10570`：2026-08-13 编译交付（经典模式下原生 EPUB 布局生成空页时回退到同章 HTML，兼容本应用导出的缺失封面资源 EPUB）。
- `3.26.081327c` / `10564`：2026-08-13 编译交付（修复选区手柄"球/杆乱飞"：手柄锚点从"图右/左边缘"改为"球杆中线"（定位 `x - width/2`），拖动映射改为"手指按在球上→杆尖跟随"（水平不再 ±width、垂直按 `v.height - 12dp` 动态取球心偏移，球+杆 28dp、仅圆球 12dp），球始终跟手、杆尖对准选区边界；反向拖动与跨页复制不受影响）。
- `3.26.081326c` / `10563`：2026-08-13 编译交付（选区手柄：手柄底部加很细的竖杆（球+杆，长边/8 的角落区域同包还含跨页复制触发区域加大）；阅读设置新增"选区颜色"（背景色，支持透明度）、"选区手柄颜色"（球+杆整体上色，支持透明度）、"选区手柄样式"（圆球加杆/仅圆球/无手柄 三选，默认球+杆；无手柄时不可拖动调整选区）。
- `3.26.081325c` / `10561`：2026-08-13 编译交付（跨页复制触发区域加大：右下角物理正方形区域，边长=屏幕长边 1/8（竖屏约纵向 1/8×横向 1/4，横屏自动反转，方屏 1/8×1/8），停留 500ms 不变）。
- `3.26.081323c` / `10559`：2026-08-13 编译交付（整页书签添加动效改为"标签尖角钉在页面顶边、从固定顶边长出来"：可见高度=下拉距离 1:1，长满 64dp 后标签固定不动、页面继续下滑，松手超过阈值添加成功后回弹期间标签保持满尺寸留在右上角；删除模式不变）。
- `3.26.081322c` / `10558`：2026-08-13 编译交付（整页书签标签常驻时被正文裁剪裁掉右半部分，标签绘制改为始终先于正文裁剪，常驻/下拉标签完整显示）。
- `3.26.081321c` / `10557`：2026-08-13 编译交付（整页书签下拉动效大修：下拉抢占提前到 8dp 并全程独占、翻页 delegate 不再抢事件（修复页面不跟手）；标签绘制移到正文裁剪前并贴页顶右上角（修复标签乱飞/被裁）；选区开始的手势与长按后拖动不再触发下拉（修复抢手势）；删除模式回弹期间标签消失；页首匹配收紧至 80% 前缀）。
- `3.26.081317c` / `10553`：2026-08-13 编译交付（新增：下拉添加/删除整页书签（翻页模式，页面跟手下移+书签从顶部延伸+松手回弹动效，右上角拟真标签按页首文字匹配显示，全局颜色可设）；跨页复制（选区时手指移到最右下角停留片刻自动翻页继续选择）；选区放大镜开关（默认关）。书签表新增 isPageBookmark 字段，DB 升级至 99）。
- `3.26.081316c` / `10552`：2026-08-13 编译交付（EPUB 导出背景色支持透明度；PDF 导出对话框回退干净）。
- `3.26.081315c` / `10551`：2026-08-13 编译交付（项目总规则落盘 AGENTS.md）。
- `3.26.081314c` / `10550`：2026-08-13 编译交付（书架"全部"分组可关闭）。
- `3.26.081313c` / `10549`：2026-08-13 编译交付（更新检查误报修复）。
- `3.26.081312c` / `10548`：2026-08-13 编译交付（增量缓存修复等）。
- `3.26.081311c` / `10547`：2026-08-13 编译交付（TXT(zip) 替换规则外挂）。
- `3.26.081310c` / `10546`：2026-08-12 编译交付（TXT 导出拆分 txt_zip）。
- `3.26.081309c` / `10545`：2026-08-12 编译交付（EPUB 媒体 0 字节修复、书签侧车）。
- `3.26.081308c` / `10544`：从 `app\build\outputs\apk\app\c\base.apk` 读取的上一包版本。
- `3.26.081306c` / `10542`：2026-08-12 编译交付（配图导入导出修复）。
- `3.26.081136c` / `10538`：2026-08-12 编译交付（音频块交互增强）。
- `3.26.081134c` / `10536`：2026-08-12 编译交付（配图保存根因修复）。
- `3.26.081132c` / `10534`：2026-08-12 编译交付（媒体类型识别）。
- `3.26.081131c` / `10533`：2026-08-12 编译交付（书签）。
- `3.26.081130c` / `10532`：2026-08-12 编译交付（配图选择器 */*）。
- `3.26.081129c` / `10531`：2026-08-12 编译交付（配图媒体扩展）。
- `3.26.081128c` / `10530`：2026-08-12 编译交付（配图目录）。
- `3.26.081127c` / `10529`：2026-08-12 编译交付（书签功能）。
- `3.26.081126c` / `10528`：2026-08-12 编译交付（配图第二轮修复）。
- `3.26.081124c` / `10526`：2026-08-12 编译交付。
- `3.26.081123c` / `10525`：2026-08-12 编译交付。
- `3.26.081122c` / `10524`：2026-08-11 交付。
- `3.26.081121c` / `10523`：2026-08-11 交付。
- `3.26.062205c` / `10491`：更早交付。

后续覆盖包必须从 `3.26.081424c` / `10592` 起步（实际编译时刻的 `MMddHH` 更大时取实际值）。

## 八、编译失败处理

1. Kotlin 增量缓存已注册冲突，或资源合并阶段某个 `build\intermediates` 目录删不掉：
   - 停掉 Gradle daemon → 删除 `app\build`、`modules\book\build`、`modules\rhino\build` → 重跑；仍失败追加 `--no-daemon`、`-Dkotlin.incremental=false`、`--max-workers=1` 冷编译定位。
2. Kotlin 编译阶段 `Native memory allocation failed` / `Kotlin daemon has been unexpectedly lost` / `Connection reset`：
   - 停 daemon → 追加 `--max-workers=1` 重跑；仍崩溃再追加 `--no-daemon` 和 `-Dkotlin.incremental=false`，并把 `gradle.properties` 中 `kotlin.incremental`、`ksp.incremental` 临时改回 `false`，稳定后恢复。

## 九、产物验证

```powershell
$apk='D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_<版本>.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk
```

预期：包名 `io.legado.app.c`、`versionName=3.26.xxxxxxc`、中文名 `阅读 C`、`native-code: arm64-v8a`、签名 `CN=Android Debug`。`apksigner` 提示部分 `META-INF` 条目未受签名保护是正常现象，退出码 0 即通过。

## 十、发布 Release（每次发布必做）

1. **发布前**：确认 APK 是 appC 变体、产物来自 `app\build\outputs\apk\app\c`；用 aapt 验证包名/versionCode 递增/versionName 格式/中文名/native-code；用 apksigner 验证签名。
2. **tag 与版本名一致**（如 `v3.26.081317c`），`target_commitish` 指向 `own` 分支最新提交。
3. **Release 正文禁止在命令行直接粘贴中文参数**（Windows 管道会把中文变问号）。正文必须写成 UTF-8 无 BOM 的 JSON 文件：
   - 用 Python 生成，注意设置 `PYTHONUTF8=1`（否则 Windows 下 Python 按 GBK 读源文件，中文变乱码）；`json.dump(payload, f, ensure_ascii=False, indent=2)`。
   - 用 `curl.exe --data-binary "@文件"` 提交（PowerShell 里必须写 `curl.exe`，避免被解析成 Invoke-WebRequest 别名）。
4. **APK 资产上传**：`Content-Type: application/octet-stream`，`curl.exe --data-binary @apk` 上传二进制，禁止经过 PowerShell 文本管道。
5. **发布后必须复查**：
   - 拉取刚创建的 release，检查 name/body 中文是否乱码、`draft=false`、`prerelease=false`、`tag_name` 与版本名一致、`target_commitish` 指向最新提交。
   - 打开 GitHub 网页版 Release 页面实际查看中文是否正常、排版是否正确。
   - 确认资产列表里有 APK，`size` 与本地一致，下载链接返回 200。
   - 如乱码：用 API `PATCH /repos/.../releases/{id}` 修正（UTF-8 无 BOM JSON），修正后重新抓网页复查。
6. PowerShell 的 `>` 重定向和 `Out-File` 默认写 UTF-16，会破坏 JSON/二进制，必须用 Python 或显式 UTF-8 无 BOM 写文件。

## 十一、git 提交

- 提交前检查 `git status`、`git diff`、`git log`；只暂存要提交的文件，不提交临时文件（如 `$logErr`、`$logOut`、构建日志、APK）。
- 提交信息简洁，写清楚改动内容，风格与仓库历史一致。

## 十二、设置默认值必须前后端一致（防"假关闭/假开启"）

> 2026-08-14 修复音量键翻页问题时的经验教训，务必遵守。

1. **每个设置项有两处默认值，改任何一处时必须同时核对另一处**：
   - **前端（界面显示）**：`app\src\main\res\xml\pref_config_*.xml` 里的 `android:defaultValue`。
   - **真正生效**：`AppConfig.kt` 里的 getter（`getPrefBoolean(PreferKey.xxx, <默认>)`、`getPrefInt`、`getPrefString`），以及散落在各文件的 `getPrefBoolean("xxx", <默认>)` / `getPrefBoolean(PreferKey.xxx)`（无第二参默认 `false`）。
2. **两处不一致的症状**：设置界面显示"开/关"与实际行为相反，即"假关闭/假开启"——用户看到开关是关的，实际却生效；或看到是开的，实际却没作用。这属于隐蔽 bug，用户会反复遇到。
3. **改默认值时**：要么同时改 XML 的 `android:defaultValue` 和代码 getter 的默认值，使其一致；要么至少保证"界面显示的默认值 == 代码读取的默认值"。
4. **如何排查**：对每个带 `android:defaultValue` 的 key，全库搜索其所有 `getPrefBoolean/Int/String(PreferKey.xxx 或 "xxx")` 读取点，逐一对比默认值；特别注意 `getPrefBoolean(PreferKey.xxx)` 不带第二参数 = 默认 `false`，极易与 XML 默认 `true` 冲突。
5. **本次已修复**（3.26.081411c / 10573）：
   - `volumeKeyPageOnPlay`：XML 默认 `false`，AppConfig 读取默认原为 `true` → 改为 `false`（界面显示关、实际仍翻页 → 现在一致）。
   - `updateCheckOnStart`：XML 默认 `true`，MainActivity / MyFragment 读取默认原为 `false` → 改为 `true`（界面显示开、实际不检查更新 → 现在一致）。
   - `coverShowName` / `coverShowNameN`：封面设置页"显示作者"开关可用性判断读取默认原为 `false`，与 XML 默认 `true` 不一致 → 改为 `true`。
6. 另有多处字符串/整数类默认不一致（如 `screenOrientation`、`clickImgWay`、`doublePageHorizontal` XML 默认 `"0"` 而代码读 null 走 else 分支），因行为与默认项等价暂未改，改这些设置默认时需一并核对。

## 十三、弹窗局部模糊的已确认实现规则

1. 弹窗、PopupWindow、原生菜单和阅读页菜单必须先进入不可见准备态，再从宿主窗口取得对应矩形的底图、完成模糊背景安装，最后才恢复可见或启动进入动画；不能先显示实色面板再异步替换背景。
2. 局部模糊只允许作用于真实弹窗面板或专门的玻璃底层 View。不能把弹窗根容器、宿主根布局、锚点按钮或全屏内容容器当作模糊目标；找不到可靠目标时应放弃模糊，不能扩大到全局。
3. PixelCopy 的源矩形必须与宿主窗口真实重叠：同窗口使用窗口坐标，跨窗口的 PopupWindow/Dialog 使用屏幕坐标换算到宿主窗口，并对矩形做严格相交裁剪，禁止用强制 1 像素裁剪掩盖坐标错误。
4. PixelCopy 必须使用当前项目 compileSdk 可编译的 Window 复制重载（系统内部仍走硬件复制）；只有在项目 stubs 明确提供 `PixelCopy.Request.ofWindow()` 时才可切换到构建器路径。仍需等待复制回调和模糊背景安装完成后再显示浮层。显示/隐藏期间使用代际标记，过期回调不得把已关闭或重新打开的浮层显示出来。
5. 关闭系统整屏 `DIM_BEHIND` 或系统窗口模糊只为避免整体亮度下降；弹窗的灰黑/浅灰底色、透明度和模糊度仍由统一弹窗表面组控制。

## 十三、已确认编译经验

1. **appC 版本名参数不要重复追加 `c`**：`app\build.gradle` 的 `appC` flavor 会自动追加 `versionNameSuffix 'c'`。正式编译时应传 `-PVERSION_NAME=3.26.MMddHH`（不带末尾 `c`），再用 `aapt dump badging` 确认产物为 `3.26.MMddHHc`。如果传入值已经带 `c`，产物会错误地变成 `3.26.MMddHHcc`，不能交付。
2. **Java 原生内存不足的确认处理**：若正式编译出现 `Native memory allocation failed`、`Kotlin daemon has been unexpectedly lost`、`Connection reset` 或 Gradle daemon 消失，先执行 `gradlew --stop`，确认并清理残留的 Gradle/Java 编译进程；然后使用正式的 `assembleAppC`，附加 `--no-daemon --max-workers=1 -Dkotlin.incremental=false -Dksp.incremental=false -Dkotlin.compiler.execution.strategy=in-process` 重试。每 30 秒检查进程 CPU、内存、日志和 APK 产物，不能只等待固定超时。
3. **编译失败必须先读实际错误再重跑**：如果 Kotlin 编译失败，先查看标准错误中的具体文件、行号和 unresolved reference 等真实错误，修复后再重新执行正式 APK 编译；不能把源码错误误判成内存问题，也不能只重复执行同一条命令。
4. **编译前删除旧 APK 只限于精确产物文件**：项目规则要求编译前移除旧安装包时，只能根据已确认的完整路径删除 `app\build\outputs\apk\app\c` 下的旧 APK；不得删除源码、`AGENTS.md`、构建配置或整个工作区。删除后必须重新生成并用 `aapt`、`apksigner` 验证新 APK。

## 十四、弹窗模糊规则

1. **禁止全局模糊宿主 Activity**：弹窗模糊不能把 `RenderEffect` 设置到宿主 Activity 的 `decorView`，否则会把整页壁纸和主页 UI 一起模糊。
2. **统一使用目标区域模糊**：不使用 `setBackgroundBlurRadius` / `FLAG_BLUR_BEHIND`，因为全屏或大范围宿主窗口在当前设备上会把阅读页整体模糊并改变亮度。
3. **只复制弹窗目标区域**：使用 `PixelCopy` 复制弹窗实际目标区域（普通弹窗、底部弹层、`vw_bg` 或 PopupWindow 内容），缩小后做位图模糊，再作为该目标区域的背景层；不得退回 Activity 全屏 `RenderEffect`。

## 十五、阅读页菜单模糊规则

1. 阅读主菜单、漫画菜单、搜索菜单属于宿主 Activity 内部的同窗口浮层，不会经过 Dialog 的模糊入口；必须由 `LocalPopupBlur` 在菜单动画结束后只暂时隐藏纯背景层，使用 PixelCopy 复制各自面板矩形，再把模糊图只铺回背景层。禁止隐藏菜单根节点或包含操作控件的容器，否则会产生抽动。
2. 听书菜单属于底部 Dialog；阅读页右上角更多菜单和 PopupMenu 属于独立 PopupWindow，分别走 Dialog 局部模糊和 PopupWindow 的 `mBackgroundView` 背景外壳局部模糊，禁止对阅读页根节点设置 RenderEffect。`mDecorView` 只负责承载和事件分发，不能优先作为模糊目标，否则实心背景外壳会盖住模糊图。
3. 浮层关闭、重新显示或切换主题时必须清理旧的位图背景和生命周期监听，不能把上一帧模糊图层叠加到下一次显示。
