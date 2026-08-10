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

项目当前没有 `.git` 目录。默认版本号逻辑会读取 git commit 数，因此必须显式传入 `VERSION_CODE` 和 `VERSION_NAME`。

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

.\gradlew.bat ':app:assembleAppC' '-Pabi=arm64-v8a' '-PVERSION_CODE=10490' '-PVERSION_NAME=3.26.062204' '-Dkotlin.incremental=false' '-Dkotlin.compiler.execution.strategy=in-process' --no-daemon --console=plain --warning-mode=summary
```

## 失败处理

如果出现 Kotlin 增量缓存已注册冲突，或资源合并阶段提示某个 `build\intermediates` 目录删不掉：

1. 停掉 Gradle daemon。
2. 删除项目内生成目录：`app\build`、`modules\book\build`、`modules\rhino\build`。
3. 用上面的无 daemon、关闭 Kotlin 增量编译命令重跑。

当前阅读 C 使用独立包名，构建类型是 `c`，最终包名后缀是 `.c`。版本号沿用正常递增线，不要随手写超大版本号。

## 验证

APK 预期路径：

```text
D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_3.26.062204_10490.apk
```

检查包名、版本、ABI：

```powershell
$apk='D:\AI\audio\legadoC-own\app\build\outputs\apk\app\c\legado_app_3.26.062204_10490.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
```

预期关键信息：

```text
package: name='io.legado.app.c'
versionCode='10490'
versionName='3.26.062204c'
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
