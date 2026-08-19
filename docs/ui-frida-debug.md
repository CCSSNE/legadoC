# Frida UI 调试窗口速查（雷电模拟器 / legadoC）

> 用途：给模拟器里运行的 App 注入带悬浮窗的可调节调试面板（如"背景板下移 offset"）。
> 环境：frida 16.7.19 + frida-tools 13.0.1（venv：`D:\AI\audio\frida-env`），frida-server 必须同版本 push 模拟器并启动。frida 17 在 QJS/V8 下无 Java 桥，不可用。

## 注入脚本要点

1. **Java wrapper 陷阱（本机 frida 16.7.19 实测）**
   - 实例方法/字段的属性访问返回 `undefined` → 一律 `Java.use(Cls).method.call(instance, ...)`。
   - 静态成员（`Color.WHITE`、`FrameLayout.LayoutParams.MATCH_PARENT`、`ActivityThread.currentActivityThread()`）全不可用 → 常量硬编码：`MATCH_PARENT=-1`、`WRAP_CONTENT=-2`、`Gravity.CENTER=0x11`、颜色转 signed int（`#D920262E → -652204498`）；拿 Activity 用 `Java.choose` 而非静态方法。
   - JS string 传 String 参数（中文）失败 → 包 `JString.$new(txt)`；字号用 `setTextSize(0, px)`（COMPLEX_UNIT_PX），否则 14sp 会被密度放大 3.5×。
   - Java null 就是 JS `null`，用 `v === null` 判断。

2. **LayoutParams 字段写失效**：`lp.gravity/height/topMargin` 赋值是静默 no-op → 边距用 `MarginLayoutParams.setMargins.call(lp, l,t,r,b)` 后 `View.setLayoutParams.call(v, lp)`；改高度/平移优先 **translationY** 或 **整体 marginTop**。
   - **坑**：`setPadding(top)` 只影响内容起点，字幕滚动到中/后段时屏幕上无任何变化（本次"没效果"根因）。要让"文字区整体下移"必须平移滚动容器本身（marginTop）。

3. **悬浮窗**
   - 挂到 `decor.findViewById(android.R.id.content)`（ContentFrameLayout）；每次注入前先移除 content 的 idx≥1 子视图（残留面板不随会话结束消失，且按钮会指向已卸载 JS → 点击崩溃）。
   - 按钮用 clickable TextView（Button 有 48dp 最小高度）；按钮识别用 `View.setTag(Integer)` + 回调里 `Integer.intValue(getTag())`——**别用 frida wrapper 引用 `===` 比较**（不一定成立）。
   - 点击回调 = `Java.registerClass` 实现 `View$OnClickListener`，全局只注册一次、实例可复用。
   - 面板文本必须含「Frida 补丁已注入并生效」，生命周期=会话生命周期（无失效时间）。

4. **崩溃规避**：onCreate hook 里立即建复杂 UI 曾 SIGABRT → `Handler.postDelayed(runnable, 300)` 再挂面板；所有 UI 操作 `Java.scheduleOnMainThread`。

5. **会话规则**：同一进程只允许一个 frida 会话可靠用 Java 桥（第二会话 `Java.choose` 空转/树不全）→ 调试/查询脚本要么独占、要么一次做完 detach。

## 验证方法

- RPC `exports` + 驱动自检：`set_offset → get_state` 读回，确认读写对称。
- 交互链路必须 **adb `input tap` 真实点击**按钮绝对坐标（`View.getLocationOnScreen(int[2])`，别用 getLeft 相对坐标）→ 读回状态变化。
- 字幕区旋转动画导致 `uiautomator dump` idle 失败 → 用 frida RPC/视图读取代替。
- 落源码前先复位残留 padding/margin（布局原始值 0）再设 offset。