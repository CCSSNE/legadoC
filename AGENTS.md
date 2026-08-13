# 项目总规则（阅读 C / legadoC）

## 编译与验证

1. 验证任何代码改动，**一律直接编译正式版**：`.\gradlew.bat ':app:assembleAppC'`（带 `-Pabi=arm64-v8a`、递增的 `-PVERSION_CODE` / `-PVERSION_NAME`），产物必须来自 `app\build\outputs\apk\app\c`。
2. **禁止**只跑 `compileAppCKotlin`、`processAppCResources` 等中间任务来"验证"改动——它们不产出 APK，跑完也没有交付物。具体流程见 `docs/BUILD_LEGADO_C_ANDROID.md`。
3. 版本号必须按 `docs/BUILD_LEGADO_C_ANDROID.md` 第 7 条基线单调递增，编译前先确认基线、删除旧包。

## 长命令实时监控（强制）

1. 任何可能超过 30 秒的命令（编译、打包、长脚本等），**必须加实时监控**，不允许干等或只启动不管。
2. **每 30 秒探测一次**：检查进程是否存活、CPU 是否在增长、日志/产物是否有更新。
   - 进程 CPU 持续增长、日志在写 = 正常运行，继续等待。
   - CPU 停滞且无日志更新 = 疑似卡死，立即终止并报告，不干等。
3. **命令不能超过 30 秒超时**：单次等待/探测间隔不超过 30 秒；超时未完成必须主动检查状态并向用户说明，而不是无限死等。
4. 编译结束（成功或失败）后：执行 `.\gradlew.bat --stop` 并清理残留 Gradle/Kotlin daemon 进程（按 PID `Stop-Process`），不留待机进程占内存。
5. 任何异常（BUILD FAILED、OOM、进程异常退出、日志停滞）必须明确告知用户原因和下一步，不允许静默忽略。
