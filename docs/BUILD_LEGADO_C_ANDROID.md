# 阅读 C 安卓编译记录

第一规则：禁止使用 debug 编译，交付给用户安装时只能编译 `appC` 变体，产物必须来自 `app\build\outputs\apk\app\c`。

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
2. 每次重新编译安装包前，先确认用户手机已安装版本的 `versionCode`。能连设备时用 `adb shell dumpsys package io.legado.app.c` 查；不能连设备时，用最近一次已交付 APK 的 `versionCode` 做基线。
3. 新包的 `VERSION_CODE` 必须大于用户手机已安装版本；不能只看当前输出目录，更不能复用旧值。
4. `VERSION_NAME` 也必须每次交付递增，不能沿用上一包的可见版本名。
5. 如果只是跑普通 debug 编译验证，不交付给用户安装，必须明确说明那不是覆盖安装包。
6. 禁止把 `app\build\outputs\apk\app\debug` 的 `.debug` 包当成阅读 C 包交付。
7. 已交付的 `3.26.062205c` 是 `10491`；`3.26.081117c` / `10521` 已交付；当前最新测试包是 `3.26.081120c` / `10522`（2026-08-11），后续覆盖包必须从 `3.26.081121c` / `10523` 起步。

当前阅读 C 使用独立包名，构建类型是 `c`，最终包名后缀是 `.c`。版本号沿用正常递增线，不要随手写超大版本号。

## 编译前必须删除旧安装包

- 编译之前，必须删除原来的安装包。
- 禁止把老的安装包改为新的包名。
- 必须完全删除老的安装包。

## 编译命令

PowerShell 必须显式使用 UTF-8，并把 Gradle 缓存放在 D 盘，避免 Windows 下 KSP/增量缓存跨盘路径问题。

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

$versionCode=10522
$versionName='3.26.081120'
.\gradlew.bat ':app:assembleAppC' '-Pabi=arm64-v8a' "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" '-Dkotlin.incremental=false' '-Dkotlin.compiler.execution.strategy=in-process' --no-daemon --console=plain --warning-mode=summary --max-workers=1
```

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
D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_3.26.081120_10522.apk
```

检查包名、版本、ABI：

```powershell
$apk='D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_3.26.081120_10522.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
```

预期关键信息：

```text
package: name='io.legado.app.c'
versionCode='10522'
versionName='3.26.081120c'
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
