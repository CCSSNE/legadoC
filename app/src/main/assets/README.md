<table align="center">
  <tr>
    <td align="center"><img src="https://github.com/CCSSNE/legadoC/raw/own/docs/PixPin_2026-08-11_12-39-35.png" width="100%" alt="界面截图 1" /></td>
    <td align="center"><img src="https://github.com/CCSSNE/legadoC/raw/own/docs/PixPin_2026-08-11_12-40-52.png" width="100%" alt="界面截图 2" /></td>
    <td align="center"><img src="https://github.com/CCSSNE/legadoC/raw/own/docs/PixPin_2026-08-11_12-42-01.png" width="100%" alt="界面截图 3" /></td>
  </tr>
</table>

**交流群 有bug 或者建议可以加入 1101873338 主要优化听书体验**

**好用的话，点个 star，谢谢啦**

## 更新记录

### 2026-08-11

**书架**

- 新增书架合集功能：合集以封面拼贴展示，可进入合集管理书籍，支持嵌套子合集。
- 新增书架长按操作栏：长按书籍或合集进入选择模式，可加入合集、删除、拖动排序。
- 主界面右上角新增全选按钮（选择模式显示）：未选满时点击全选，选满时再点一下全部取消，全选后显示计数，如“全选(13,2)”。
- 合集内的全选按钮与合集标题对齐，操作逻辑与主界面一致。
- 多选拖拽时，其余书籍瞬间收束堆叠到起始书下方，收束完成后保持堆叠不散开。
- 合集封面：书籍不足 4 本时，空位保持空白占位，不放大已有封面。

**阅读与听书**

- 编辑内容：本地 TXT 打开完整文件，几 MB 大文本采用分块懒加载，编辑流畅；保存后重新做目录匹配。
- 编辑内容自动定位到当前阅读位置：在哪个页面打开，就跳到对应位置。
- 听书“需要后台权限保持服务”的通知弹窗整个应用只弹一次，不再反复打扰。

**设置与文件打开**

- 新增“打开文件自动加入书架”开关（工具与关于 → 其它设置）：可选“是 / 否 / 每次选择”；选“否”直接阅读不加入书架，选“每次选择”会先询问是否加入书架，确认后再选择保存位置。

**细节体验**

- 长按进入选择模式时短促震动一次；抬起后再次按下并拖动（第二次长按）时也震动一次。
- 日志弹窗新增一键复制按钮。

**修复**

- 修复听书时手动翻页后，选中文字点“从本段开始朗读”，红色朗读焦点不跟随到最新段落的问题。

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
