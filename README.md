**交流群 有bug 或者建议可以加入 1101873338 主要优化听书体验**

## 更新记录

### 2026-08-10

- 应用改为可与阅读 R 共存的独立包：包名 `io.legado.app.c`，中文应用名 `阅读 C`。
- 跑通 Android 编译路径，当前 APK 输出到 `app/build/outputs/apk/app/c/legado_app_3.26.062204_10490.apk`。
- 修复异步听书时，阅读页面停留在其它章节会导致听书续读跳章的问题。
- 修复书架选择“全部”时不显示小说，必须切到“小说”分类才显示的问题。
- 去除非必要的自动说明弹窗，保留手动入口可查看帮助。
- 去除退出听书页面时询问“是否后台继续阅读”的弹窗，默认后台继续阅读。
- 文本选择菜单里的“朗读”改为从选中文本所在段落开始连续朗读。
- 听书进行中支持双击正文段落，从该段落开始朗读。
- 在阅读设置的“点击区域设置”下面新增“朗读双击判定时间”，默认 200ms，可在 120ms 到 600ms 之间调整；时间越短单击响应越快，双击要求越高。

A 仓库同步调查范围：`Rimchars/legado` 从共同起点 `archive-v3-3.26.0509`（`e786392e`，2026-05-09）扫描到 `rim/main` 的 `aa5cda3a`（2026-08-07）。本版本不是整体合并 A 仓库最新代码，只是从这段范围里选择性吸收低风险修复。

- A 仓库的大型 AI、世界书、多角色朗读、BGM、云中继、Compose 大重构和数据库大迁移暂不吸收。

- 从 A 仓库吸收通用输入流读取修复，避免用 `available()` 误判文件长度，并新增带上限的读取逻辑。
- 从 A 仓库吸收 data-url 图片大小限制，避免超大 base64 图片导致内存暴涨。
- 从 A 仓库吸收缓存计量修复，内存缓存按字符串、字节数组、Bitmap、集合等真实类型估算大小。
- 从 A 仓库吸收路径穿越防护修复，压缩包解压和 JS 文件访问改为严格判断同目录或子目录。
- 从 A 仓库吸收 EPUB/MOBI 封面采样解码修复，避免超大封面直接全尺寸解码导致 OOM。
- 从 A 仓库吸收 UMD 解析修复：截断读取报错、zlib 解压防卡死、输入流解析后关闭、限制不可信 UMD 内容分配。

本版本实际吸收的 A 仓库提交：

- `908883536`：`fix(io): add bounded stream reads`
- `15befdf1b`：`fix(data-url): limit decoded image payloads`
- `8f8861521`：`fix(cache): size memory cache entries by type`
- `76d468ea2`：`fix(security): harden path containment checks`
- `6112e52df`：`fix(image): sample decode user images`
- `6440d9c59`：`fix(umd): reject truncated stream reads`
- `ec81a20a8`：`fix(umd): fail stalled zlib decompression`
- `42db1f27c`：`fix(umd): close source stream after parsing`
- `eed38599d`：`fix(umd): bound untrusted parser allocations`

已看过但本版本未吸收：

- `cdc39b11b`：阅读图片尺寸查询超时修复，值得后续单独做，但会动阅读排版核心。
- `105e8c4e`：丢弃已脱离页面的渲染任务，依赖 A 仓库后续渲染代际机制，C 当前不能硬套。
