# 阅读 C 发布指南

每次发布 GitHub Release 前、中、后都必须按本指南执行，重点是发布完成后要实际复查网页，不能发完就不管。

## 一、发布前检查

1. 确认要发布的 APK 是 `appC` 变体，产物必须来自 `app\build\outputs\apk\app\c`。
2. 用 `aapt dump badging` 验证：
   - 包名必须是 `io.legado.app.c`
   - `versionCode` 必须比上一次已交付版本递增（参考 `docs/BUILD_LEGADO_C_ANDROID.md` 的版本号基线）
   - `versionName` 必须递增且格式为 `3.26.xxxxxxc`
   - 中文应用名必须是 `阅读 C`
   - `native-code` 必须是 `arm64-v8a`
3. 用 `apksigner verify --print-certs` 验证签名，确认 `CN=Android Debug`。

## 二、发布 Release

1. tag 名称与版本名一致，例如 `v3.26.081122c`，`target_commitish` 指向 `own` 分支最新提交。
2. Release 正文禁止在命令行里直接粘贴中文参数，Windows 命令行/PowerShell 管道会把中文变成问号。
3. 正文必须写成 UTF-8 无 BOM 的 JSON 文件：
   - 用 Python 生成：`json.dump(payload, f, ensure_ascii=False, indent=2)`，文件编码 `utf-8`。
   - 再用 `curl.exe --data-binary "@文件"` 提交，保证字节原样上传。
4. APK 资产上传：`Content-Type: application/octet-stream`，用 `--data-binary @apk` 上传二进制，禁止经过 PowerShell 文本管道。

## 三、发布后必须复查（每次发布必做）

1. 用 GitHub API 拉取刚创建的 release，检查：
   - `name`、`body` 里的中文是否变成 `\ufffd`、`?` 或乱码
   - `draft=false`、`prerelease=false`
   - `tag_name` 与版本名一致，`target_commitish` 指向最新提交
2. 打开 GitHub 网页版 Release 页面（`curl -L` 抓 HTML），实际查看：
   - 正文中文是否正常，有没有问号/乱码
   - 标题、分类（【书架】等）、列表排版是否正确
3. 确认资产列表里有 APK，`size` 与本地文件一致，下载链接返回 `200`。
4. 如发现乱码或排版问题：用 API `PATCH /repos/.../releases/{id}` 修正正文（同样用 UTF-8 无 BOM JSON），修正后重新抓网页复查，直到正常。

## 四、常见坑

- PowerShell 的 `>` 重定向和 `Out-File` 默认写 UTF-16，会破坏 JSON/二进制，必须用 Python 或显式 UTF-8 无 BOM 写文件。
- PowerShell 里调用 curl 必须写 `curl.exe`，避免被解析成 `Invoke-WebRequest` 别名。
- 创建正文 JSON 必须 `ensure_ascii=False`，否则中文会被转成 `\uXXXX`。
- tag 与版本名要一致，避免 Release 页面对不上号。
- 发布后不复查 = 发布失败；乱码和排版问题只有在网页上实际看才能发现。
