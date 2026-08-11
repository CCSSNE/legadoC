# 阅读 C 安卓编译记录

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
4. `VERSION_NAME` 可以沿用当天主版本名，但 `VERSION_CODE` 必须递增。
5. 如果只是跑普通 debug 编译验证，不交付给用户安装，必须明确说明那不是覆盖安装包。
6. 禁止把 `app\build\outputs\apk\app\debug` 的 `.debug` 包当成阅读 C 包交付。
7. 已交付的 `3.26.062205c` 是 `10491`；当前最新测试包是 `3.26.081107c` / `10497`，后续覆盖包必须从 `10498` 起步。

当前阅读 C 使用独立包名，构建类型是 `c`，最终包名后缀是 `.c`。版本号沿用正常递增线，不要随手写超大版本号。

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

$versionCode=10497
$versionName='3.26.081107'
.\gradlew.bat ':app:assembleAppC' '-Pabi=arm64-v8a' "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" '-Dkotlin.incremental=false' '-Dkotlin.compiler.execution.strategy=in-process' --no-daemon --console=plain --warning-mode=summary
```

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
D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_3.26.081107_10497.apk
```

检查包名、版本、ABI：

```powershell
$apk='D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_3.26.081107_10497.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
```

预期关键信息：

```text
package: name='io.legado.app.c'
versionCode='10497'
versionName='3.26.081107c'
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
