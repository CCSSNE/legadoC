# 阅读 C 安卓编译记录

第一规则：禁止使用 debug 编译，交付给用户安装时只能编译 `appC` 变体，产物必须来自 `app\build\outputs\apk\app\c`。

> 已加构建钩子（2026-08-12）：任何 assemble/bundle/install/package 类 debug 任务都会在执行前被拦截并中止构建，提示重新阅读本文档。

## 目标

从 `D:\AI\audio\legadoC-own` 编译可与阅读 R、默认 debug 包共存的阅读 C APK。

阅读 C 使用独立包名后缀：

```text
io.legado.app.c
```

中文显示名：

```text
阅读 C
```

## 本机环境

- JDK 17: `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`
- Android SDK: `D:\AI\audio\android-sdk`
- Gradle user home: `D:\AI\audio\android-gradle-user-home`
- Gradle wrapper: `8.14.4`
- compileSdk: `36`

覆盖安装给用户测试时，必须显式传入 `VERSION_CODE` 和 `VERSION_NAME`。不要依赖默认版本号逻辑；默认逻辑会受 git 提交数和编译时间影响，容易打出不能覆盖升级的包。

## 覆盖编译版本号规则

每次编译给用户安装的阅读 C APK，都按覆盖升级处理：

1. 只编译 `appC` 变体，产物目录必须是 `app\build\outputs\apk\app\c`。
2. 每次重新编译安装包前，先确认用户手机已安装版本的 `versionCode`。能连设备时用 `adb shell dumpsys package io.legado.app.c` 查；不能连设备时，用第 7 条的“最近一次已交付”基线。
3. 新包的 `VERSION_CODE` 必须大于用户手机已安装版本；不能只看当前输出目录，更不能复用旧值。按已交付线每次 `+1`（当前基线 `10532` → 下一包 `10533`）。
4. `VERSION_NAME` 格式是 `3.26.MMddHH`（默认按 GMT+8 编译时刻生成，例如 08-11 23 时 → `081123`），并且每次交付必须大于上一包。也可以沿用/顺延上一包名加一（如 `081123` → `081124`），本次 `3.26.081123` 就是按基线顺延取名、而非当天实际时刻。
5. 如果只是跑普通 debug 编译验证，不交付给用户安装，必须明确说明那不是覆盖安装包。
6. 禁止把 `app\build\outputs\apk\app\debug` 的 `.debug` 包当成阅读 C 包交付。
7. 已交付基线（新 → 旧）：
   - `3.26.081130c` / `10532`：2026-08-12 编译交付（配图选择器放开为 `*/*`：图片/视频/音频一次多选，兼容 ROM 文件选择器对 MIME 组合过滤导致音频选不到的问题；保存前拦截非媒体文件并提示）。
   - `3.26.081129c` / `10531`：2026-08-12 编译交付（配图扩展为图片/视频/音频：选择器多选媒体、音频独立块播放、视频全屏播放/滑走停止/单视频禁滑+横屏、目录页视频帧与音频音符+时长、PDF/EPUB/TXT 导出媒体并再导入恢复）。
   - `3.26.081128c` / `10530`：2026-08-12 编译交付（配图目录多选/全选、单图/双图/一行三张/一行四张/两行两列四宫格、点击区域中间竖排默认菜单、文本菜单配图项杜绝闪现，并修复四宫格与书签导出相关编译错误）。
   - `3.26.081127c` / `10529`：2026-08-12 编译交付（书签功能：正文样式渲染修复、段落书签、备注气泡、文字变色、删除线、气泡透明度设置）。
   - `3.26.081126c` / `10528`：2026-08-12 编译交付（配图功能第二轮修复：图片多选、全屏黑色背景、缩放卡顿手势冲突、单图按钮文案、目录标签切换崩溃）。
   - `3.26.081124c` / `10526`：2026-08-12 编译交付（从 `F:\下载\base.apk` 读取上包 `10525 / 3.26.081123c` 后 +1）。
   - `3.26.081123c` / `10525`：2026-08-12 编译交付。
   - `3.26.081122c` / `10524`：2026-08-11 交付。
   - `3.26.081121c` / `10523`：2026-08-11 交付。
   - `3.26.062205c` / `10491`：更早交付。
   后续覆盖包必须从 `3.26.081131c` / `10533` 起步（实际编译时刻的 `MMddHH` 更大时取实际值）。
8. 删除旧包本身不提供版本号：删除动作只负责清空产物目录，版本号必须在删除前从 adb 或第 7 条基线确认好。第 7 条是“最近一次已交付”的唯一持久记录，每次交付后必须当场更新，否则下一包会复用旧值、破坏单调递增。

当前阅读 C 使用独立包名，构建类型是 `c`，最终包名后缀是 `.c`。版本号沿用正常递增线，不要随手写超大版本号。

## 编译前必须删除旧安装包

- 编译之前，必须删除原来的安装包。
- 禁止把老的安装包改为新的包名。
- 必须完全删除老的安装包。
- 删除前先按版本规则确认好版本号（adb 或第 7 条基线）；删除动作本身不提供版本号来源，删完旧包后基线只能靠第 7 条。

## 编译命令

PowerShell 必须显式使用 UTF-8，并把 Gradle 缓存放在 D 盘，避免 Windows 下 KSP/增量缓存跨盘路径问题。

版本号必须按“覆盖编译版本号规则”取最新值，下面的数值是最近一次实际使用的值，不要照抄。

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

$versionCode=10531
$versionName='3.26.081129'
.\gradlew.bat ':app:assembleAppC' '-Pabi=arm64-v8a' "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" '-Dkotlin.incremental=false' '-Dkotlin.compiler.execution.strategy=in-process' --no-daemon --console=plain --warning-mode=summary --max-workers=1
```

2026-08-12 实测结果（`VERSION_CODE=10531`、`VERSION_NAME=3.26.081129`）：

```text
BUILD SUCCESSFUL in 4m 52s
75 actionable tasks: 9 executed, 66 up-to-date
```

产物 `legado_app_3.26.081129_10531.apk` 验证通过：包名 `io.legado.app.c`、`versionCode=10531`、`versionName=3.26.081129c`；已安装到雷电模拟器 `emulator-5554` 覆盖升级成功。

2026-08-12 实测结果（`VERSION_CODE=10532`、`VERSION_NAME=3.26.081130`）：

```text
BUILD SUCCESSFUL in 4m 8s
75 actionable tasks: 13 executed, 62 up-to-date
```

产物 `legado_app_3.26.081130_10532.apk` 验证通过：包名 `io.legado.app.c`、`versionCode=10532`、`versionName=3.26.081130c`、中文名 `阅读 C`、`native-code: arm64-v8a`。

2026-08-12 实测结果（`VERSION_CODE=10530`、`VERSION_NAME=3.26.081128`）：

```text
BUILD SUCCESSFUL in 4m 22s
75 actionable tasks: 7 executed, 68 up-to-date
```

产物 `legado_app_3.26.081128_10530.apk` 验证通过：包名 `io.legado.app.c`、`versionCode=10530`、`versionName=3.26.081128c`；已安装到雷电模拟器 `emulator-5554` 覆盖升级成功。

2026-08-12 实测结果（`VERSION_CODE=10526`、`VERSION_NAME=3.26.081124`）：

```text
BUILD SUCCESSFUL in 3m 28s
75 actionable tasks: 13 executed, 62 up-to-date
```

产物 `legado_app_3.26.081124_10526.apk` 验证通过：包名 `io.legado.app.c`、`versionCode=10526`、`versionName=3.26.081124c`、中文名 `阅读 C`、`native-code: arm64-v8a`、签名 `CN=Android Debug`；已安装到雷电模拟器验证可覆盖升级、可启动。

## 分阶段编译记录

如果不想把整条编译压成一个黑盒，可以先跑前置资源和清单阶段，确认资源合并、清单处理、R 文件生成没有问题，再继续代码编译和打包阶段。

阶段 1 已验证可用：资源和清单处理。

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

$versionCode=10521
$versionName='3.26.081117'
.\gradlew.bat ':app:processAppCResources' '-Pabi=arm64-v8a' "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" '-Dkotlin.incremental=false' '-Dkotlin.compiler.execution.strategy=in-process' --no-daemon --console=plain --warning-mode=summary --max-workers=1
```

2026-08-11 实测结果：

```text
BUILD SUCCESSFUL in 23s
32 actionable tasks: 9 executed, 23 from cache
```

这一阶段覆盖的流程是：检查库模块元数据，生成和合并 appC 资源，处理导航资源，处理 appC 和库模块清单，编译库模块资源，解析本地资源，生成库模块 R 文件，最后处理 appC 资源。

阶段 2 已验证可用：代码生成和代码编译。

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

$versionCode=10521
$versionName='3.26.081117'
.\gradlew.bat ':modules:book:compileDebugKotlin' ':modules:book:compileDebugJavaWithJavac' ':modules:rhino:compileDebugKotlin' ':modules:rhino:compileDebugJavaWithJavac' ':app:kspAppCKotlin' ':app:compileAppCKotlin' ':app:compileAppCJavaWithJavac' '-Pabi=arm64-v8a' "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" '-Dkotlin.incremental=false' '-Dkotlin.compiler.execution.strategy=in-process' --no-daemon --console=plain --warning-mode=summary --max-workers=1
```

2026-08-11 实测结果：

```text
BUILD SUCCESSFUL in 2m 52s
45 actionable tasks: 2 executed, 1 from cache, 42 up-to-date
```

这一阶段覆盖的流程是：先确认资源和清单阶段已就绪，再处理库模块编译产物，运行 appC 的代码生成，编译 appC Kotlin，预编译 Java，编译 appC Java，最后复制 Room schema。实测会出现 Kotlin/Java 警告和 `Detected multiple Kotlin daemon sessions` 提示；只要退出码为 0，且没有错误堆栈，这一阶段通过。

阶段 3 已验证可用：dex、资源合并、so 合并、签名和 APK 输出。

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

$versionCode=10521
$versionName='3.26.081117'
.\gradlew.bat ':app:packageAppC' ':app:createAppCApkListingFileRedirect' ':app:assembleAppC' '-Pabi=arm64-v8a' "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" '-Dkotlin.incremental=false' '-Dkotlin.compiler.execution.strategy=in-process' --no-daemon --console=plain --warning-mode=summary --max-workers=1
```

2026-08-11 实测结果：

```text
BUILD SUCCESSFUL in 1m 20s
75 actionable tasks: 24 executed, 6 from cache, 45 up-to-date
```

这一阶段覆盖的流程是：合并 assets，压缩 assets，处理 desugar，构建 dex，合并 Java 资源，检查重复类，合并外部、库和项目 dex，合并 JNI 目录和 native so，处理 debug symbol，验证签名配置，写入 APK 元数据，打包 appC，生成 APK 列表，最后完成 assembleAppC。

实测 `strip` 阶段会提示部分 so 无法剥离 debug symbol，并按原样打包，例如 `libarchive-jni.so`、`libimage_processing_util_jni.so`、`librenderscript-toolkit.so`、`librtmp-jni.so`、`libsurface_util_jni.so`。这不是打包失败；只要后续 `packageAppC`、`assembleAppC` 成功，且验证命令通过即可交付。

2026-08-11 分阶段编译产物：

```text
D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_3.26.081117_10521.apk
```

验证通过的关键信息：

```text
package: name='io.legado.app.c' versionCode='10521' versionName='3.26.081117c'
application-label-zh-CN:'阅读 C'
application-label-zh-HK:'阅读 C'
application-label-zh-TW:'阅读 C'
native-code: 'arm64-v8a'
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
```

PowerShell 监控注意事项：后台启动编译时，标准输出和错误输出不能重定向到同一个文件。必须使用两个不同日志文件，或者不要用后台启动方式；否则编译根本不会启动，后续循环只是在空进程上假监控。

## 2026-08-12 整包编译实测

用“编译命令”整条跑 `assembleAppC`（`VERSION_CODE=10525`、`VERSION_NAME=3.26.081123`），一次跑通：

```text
BUILD SUCCESSFUL in 4m 17s
75 actionable tasks: 32 executed, 9 from cache, 34 up-to-date
```

实测经验：

- 编译前 `app\build\outputs\apk\app\c` 目录为空，没有旧 APK 可删，版本号基线完全来自本文档第 7 条。这验证了“删除旧包不等于知道版本号”：版本号必须以 adb 或第 7 条基线为准，且每次交付后第 7 条必须当场更新。
- 后台启动编译并轮询日志可行：用 `Start-Process` 拉起 `gradlew.bat`，标准输出和错误输出分别重定向到两个文件，编译结束后退出码写文件，轮询该文件判断完成；不要用固定短超时死等。
- 本次只有 Kotlin/Java 弃用警告（`ProgressDialog`、表达式体返回类型等）和 `Detected multiple Kotlin daemon sessions` 提示，无错误堆栈，退出码 0。
- `stripAppCDebugSymbols` 依旧提示 5 个 so 无法剥离 debug symbol（`libarchive-jni.so`、`libimage_processing_util_jni.so`、`librenderscript-toolkit.so`、`librtmp-jni.so`、`libsurface_util_jni.so`），按原样打包，不影响交付。
- 产物 `legado_app_3.26.081123_10525.apk` 验证通过：包名 `io.legado.app.c`、`versionCode=10525`、`versionName=3.26.081123c`、中文名 `阅读 C`、`native-code: arm64-v8a`、签名 `CN=Android Debug`（apksigner 退出码 0，`META-INF` 未受签名保护警告为正常现象）。
- 编译前后 `git status` 干净：本次没有源码改动，Room schema 无变化；临时构建脚本和日志在验证后已清理。

## 失败处理

如果出现 Kotlin 增量缓存已注册冲突，或资源合并阶段提示某个 `build\intermediates` 目录删不掉：

1. 停掉 Gradle daemon。
2. 删除项目内生成目录：`app\build`、`modules\book\build`、`modules\rhino\build`。
3. 用上面的无 daemon、关闭 Kotlin 增量编译命令重跑。

如果 Kotlin 编译阶段出现 `Native memory allocation failed`、`Kotlin daemon has been unexpectedly lost` 或 `Connection reset`：

1. 先确认并停止当前仓库相关的 Gradle/Kotlin daemon，避免旧的 6G 构建进程继续占内存。
2. 保持 `appC` 变体和递增后的 `VERSION_CODE` 不变。
3. 在编译命令后追加 `--max-workers=1` 重跑；这会慢一点，但能降低并发内存占用。

## 验证

APK 预期路径：

```text
D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_3.26.081124_10526.apk
```

检查包名、版本、ABI：

```powershell
$apk='D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_3.26.081124_10526.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
```

预期关键信息：

```text
package: name='io.legado.app.c'
versionCode='10526'
versionName='3.26.081124c'
application-label-zh-CN:'阅读 C'
application-label-zh-HK:'阅读 C'
application-label-zh-TW:'阅读 C'
native-code: 'arm64-v8a'
```

检查 debug 签名：

```powershell
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk
```

预期签名：

```text
CN=Android Debug
```

`apksigner` 可能提示部分 `META-INF` 条目未受签名保护；只要命令退出码为 0，且能打印 `CN=Android Debug`，本次 debug APK 签名验证通过。
