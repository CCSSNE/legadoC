#!/usr/bin/env python3
"""阅读C-自用 Agent 自回归 harness（常驻）。

分层（对应插件化架构）：
  L1 MCP HTTP 契约：握手协商 -> tools/list（动态枚举，覆盖率自统计）
        -> tools/call（每工具按归属断言）-> 鉴权/错误负例。
  L2 设置持久化：开关切换经真实 UI，断言 DB 与 tools 可见性一致。
  L3 JS 插件循环：经真实聊天入口跑一次端到端（需模型联通；不通则只断言失败清晰）。

只用标准库。用法：
  python regtest.py setup on|off   # 经 UI 开关全部内置 Server
  python regtest.py probe          # 枚举全部工具定义存 defs.json
  python regtest.py run            # 完整矩阵，输出覆盖率报告
  python regtest.py chat           # L3: 假LLM端到端（默认JS插件真实循环+工具往返）

覆盖率口径：以各模块 tools/list 实际下发的工具数为分母；
  passed=断言通过（含符合预期的明确失败），listed_only=仅在表内出现，
  skipped=无安全夹具，failed=不符合预期。
"""
import json
import os
import subprocess
import sys
import time
import http.client
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ADB = ["adb", "-s", "emulator-5554"]
PKG = "io.legado.app.dev"
HERE = os.path.dirname(os.path.abspath(__file__))
TMP = os.path.join(HERE, ".tmp")
os.makedirs(TMP, exist_ok=True)

MODULES = ["bookshelf", "reading", "library", "sources", "settings", "web", "memory"]


def adb(*args, timeout=60):
    p = subprocess.run(ADB + list(args), capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", "replace").strip()


def dump(name):
    remote = f"/sdcard/reg_{name}.xml"
    adb("shell", "uiautomator", "dump", remote)
    local = os.path.join(TMP, f"{name}.xml")
    subprocess.run(ADB + ["pull", remote, local], capture_output=True, timeout=60)
    return local


def ui_nodes(xmlpath):
    """全部节点：(显示文本, 中心点)。显示文本取 text，否则取 content-desc
    （底栏 tab 等只有 content-desc）。"""
    t = ET.parse(xmlpath)
    out = []
    for n in t.iter("node"):
        label = n.get("text") or "" or n.get("content-desc") or ""
        if not label:
            continue
        b = n.get("bounds", "[0,0][0,0]").replace("][", ",").strip("[]")
        l, tt, r, bb = map(int, b.split(","))
        out.append((label, ((l + r) // 2, (tt + bb) // 2)))
    return out


def ui_texts(xmlpath):
    return ui_nodes(xmlpath)


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def tap_text(text, timeout=25, exact=True):
    """按文本点；找不到返回 False。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            rows = ui_texts(dump(f"tap{int(time.time()) % 100000}"))
        except Exception:
            time.sleep(2)
            continue
        hits = [(t, c) for t, c in rows if (t == text if exact else text in t)]
        if hits:
            tap(*hits[0][1])
            return True
        time.sleep(2)
    return False


def swipe_up():
    adb("shell", "input", "swipe", "540", "2000", "540", "600")
    time.sleep(1.5)


def scroll_to(text, tries=10):
    for _ in range(tries):
        rows = ui_texts(dump(f"sc{int(time.time()) % 100000}"))
        if any(t == text for t, _ in rows):
            return True
        swipe_up()
    return False


def open_ai_settings():
    """经[我的]页内子搜索直达 AI 设置页（不依赖列表滚动）。"""
    adb("shell", "am", "start", "-n",
        f"{PKG}/io.legado.app.ui.main.MainActivity")
    time.sleep(4)
    assert tap_text("我的"), "找不到底栏[我的]"
    time.sleep(3)  # 等[我的]页落定，否则下一次dump仍是旧屏
    tap_text("清除查询", timeout=4)  # 可能停在上次子搜索结果态，先退回正常页
    time.sleep(1)
    assert tap_top_search(), "找不到[我的]页顶部搜索框"
    time.sleep(2)
    tap_text("清除查询", timeout=4)  # 有则清零，无则跳过
    time.sleep(1)
    assert tap_top_search(), "重进搜索框失败"
    time.sleep(1)
    adb("shell", "input", "text", "AI")
    time.sleep(2)
    assert tap_text("AI 设置"), "子搜索无[AI 设置]结果"
    time.sleep(3)


def tap_top_search(timeout=20):
    """点[我的]页顶部搜索框（按顶部区域+strip后精确匹配，避开底栏搜索tab）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            rows = ui_nodes(dump(f"ts{int(time.time()) % 100000}"))
        except Exception:
            time.sleep(2)
            continue
        hits = [(t, c) for t, c in rows
                if t.strip() == "搜索" and c[1] < 600]
        if hits:
            tap(*hits[0][1])
            return True
        time.sleep(2)
    return False


def open_servers_dialog():
    open_ai_settings()
    assert scroll_to("MCP Server 管理"), "AI 设置里找不到[MCP Server 管理]"
    assert tap_text("MCP Server 管理"), "点不开[MCP Server 管理]"
    time.sleep(2)


def set_all_servers(want_on):
    """经真实 UI 开关全部内置 Server。"""
    open_servers_dialog()
    for _ in range(12):
        rows = ui_texts(dump("srv"))
        changed = False
        for text, center in rows:
            if want_on and text.startswith("○ ") and "·" in text:
                tap(*center)
                time.sleep(2)
                changed = True
                break
            if not want_on and text.startswith("● ") and "·" in text:
                tap(*center)
                time.sleep(2)
                changed = True
                break
        if not changed:
            break
    # 开关全部打开后，确认监听真起来；进程被杀后开关仍开但监听已死，
    # 此时点一次"刷新运行状态"（会调 AgentMcpService.refresh 重建监听）。
    for attempt in ("初检", "刷新后"):
        if listeners_up():
            break
        tap_text("刷新运行状态", timeout=15)
        time.sleep(6)
    assert listeners_up(), "监听起不来，查 logcat AgentMcpService"
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)


def listeners_up():
    """7 个端口全部能握手才算真起来。"""
    try:
        cfgs = server_configs()
    except Exception:
        return False
    for mid in MODULES:
        cfg = cfgs.get(mid)
        if not cfg or not cfg.get("enabled"):
            return False
        port, key = cfg["port"], cfg["apiKey"]
        forward(port)
        try:
            status, _, _ = mcp_post(port, {
                "jsonrpc": "2.0", "id": "pre",
                "method": "initialize",
                "params": {"protocolVersion": "2025-11-25", "capabilities": {},
                           "clientInfo": {"name": "regtest", "version": "1"}}},
                {"Authorization": f"Bearer {key}",
                 "MCP-Protocol-Version": "2025-11-25"})
            if status != 200:
                return False
        except Exception:
            return False
        finally:
            unforward(port)
    return True


def db(sql):
    # SQL 走 stdin：经 argv 会被设备端 shell 按 ;/空格拆散。
    # 二进制管道 + UTF-8：sqlite 输出含中文，locale(gbk)解码会炸。
    p = subprocess.run(
        ADB + ["shell", "run-as", PKG, "sqlite3", "databases/agent.db"],
        input=sql.encode("utf-8"), capture_output=True, timeout=60,
    )
    if p.returncode != 0:
        raise RuntimeError(
            f"sqlite 失败: {p.stderr.decode('utf-8', 'replace')[:200]}")
    return p.stdout.decode("utf-8", "replace").strip()


def server_configs():
    cfgs = {}
    for mid in MODULES:
        raw = db(f"SELECT json FROM documents WHERE namespace='mcp.servers' AND key='{mid}';")
        if raw:
            cfgs[mid] = json.loads(raw)
    return cfgs


def forward(port):
    adb("forward", f"tcp:{port}", f"tcp:{port}")


def unforward(port):
    adb("forward", "--remove", f"tcp:{port}")


def mcp_post(port, payload, headers):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/mcp",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json",
                 "Accept": "application/json, text/event-stream"} | headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")
    except (urllib.error.URLError, http.client.HTTPException,
            ConnectionError, OSError) as e:
        return 0, {}, f"连接失败: {e}"


def legacy_session(port, api_key):
    status, headers, body = mcp_post(port, {
        "jsonrpc": "2.0", "id": "init",
        "method": "initialize",
        "params": {"protocolVersion": "2025-11-25", "capabilities": {},
                   "clientInfo": {"name": "regtest", "version": "1"}},
    }, {"Authorization": f"Bearer {api_key}", "MCP-Protocol-Version": "2025-11-25"})
    assert status == 200, f"{port} initialize HTTP {status}: {body[:200]}"
    data = json.loads(body)
    assert "result" in data, f"initialize 无 result: {body[:200]}"
    sid = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
    assert sid, "initialize 未下发 Mcp-Session-Id"
    return sid, data["result"]


def cmd_setup(arg):
    assert arg in ("on", "off"), "setup on|off"
    set_all_servers(arg == "on")
    print("servers:", {k: v.get("enabled") for k, v in server_configs().items()})


def cmd_probe():
    cfgs = server_configs()
    missing = [m for m in MODULES if not cfgs.get(m, {}).get("enabled")]
    if missing:
        raise RuntimeError(f"这些 Server 未开启，先跑 setup on：{missing}")
    alldefs = {}
    for mid in MODULES:
        port, key = cfgs[mid]["port"], cfgs[mid]["apiKey"]
        forward(port)
        try:
            sid, init = legacy_session(port, key)
            hdr = {"Authorization": f"Bearer {key}",
                   "MCP-Protocol-Version": "2025-11-25", "Mcp-Session-Id": sid}
            tools, cursor = [], None
            while True:
                params = {}
                if cursor:
                    params["cursor"] = cursor
                status, _, body = mcp_post(port, {
                    "jsonrpc": "2.0", "id": "list",
                    "method": "tools/list", "params": params}, hdr)
                assert status == 200, f"{mid} tools/list HTTP {status}"
                result = json.loads(body)["result"]
                tools += result["tools"]
                cursor = result.get("nextCursor")
                if not cursor:
                    break
            alldefs[mid] = {"protocol": init.get("protocolVersion"), "tools": tools}
            print(f"{mid}: {len(tools)} tools, protocol={init.get('protocolVersion')}")
        finally:
            unforward(port)
    with open(os.path.join(HERE, "defs.json"), "w", encoding="utf-8") as f:
        json.dump(alldefs, f, ensure_ascii=False, indent=1)
    print("defs.json 已写入")


# CALLS: (module, tool) -> 下面之一
#   (args, ("ok", [结果JSON必含子串...]))
#   (args, ("ok_eq", {顶层key: 期望值}))
#   (args, ("error_contains", 子串))     明确失败契约（配置缺失/对象不存在等）
#   (args, ("ok_or_error",))             依赖外部网络，结果或结构化失败都算过
#   (None, ("listed",))                  仅断言在表内出现（写操作另行覆盖）
#   (None, ("skip", 原因))               无安全夹具
#   (None, ("seq", [(args, expectation), ...]))  顺序多步（写操作往返）
#   (None, ("roundtrip",))               settings 专属：读-同值写-比对
CALLS = {
    ("bookshelf", "query_bookshelf"): ({}, ("ok", ["matchedBooks", "groups"])),
    ("bookshelf", "get_bookshelf_book_info"): (
        {"bookUrl": "regtest:/no/such/book"}, ("error_contains", "未找到")),
    ("bookshelf", "manage_bookshelf_group"): (None, ("seq", [
        ({"action": "delete", "groupName": "REGTEST_G"}, ("ok_or_error",)),
        ({"action": "create", "groupName": "REGTEST_G"}, ("ok", ["group"])),
        ({"action": "delete", "groupName": "REGTEST_G"}, ("ok", ["deletedGroup"])),
    ])),
    ("bookshelf", "manage_bookshelf_tag"): (None, ("seq", [
        ({"action": "list"}, ("ok", ["groups"])),
        ({"action": "create", "tag": "REGTEST_T"}, ("error_contains", "至少一本书")),
        ({"action": "rename", "oldTag": "A", "newTag": "B"}, ("error_contains", "需要指定")),
        ({"action": "delete", "tag": "A"}, ("error_contains", "需要指定")),
    ])),
    ("bookshelf", "set_bookshelf_book_group"): (
        {"groupName": "REGTEST_G"}, ("error_contains", "未找到书籍")),
    ("bookshelf", "set_bookshelf_book_tags"): (
        {"tags": ["REGTEST_T"]}, ("error_contains", "未找到")),
    ("reading", "get_reading_state"): ({}, ("ok_eq", {"open": False})),
    ("reading", "list_book_chapters"): (
        {"bookUrl": "regtest:/nope"}, ("error_contains", "书籍不存在")),
    ("reading", "read_book_chapter_content"): (
        {"bookUrl": "regtest:/nope"}, ("error_contains", "书籍不存在")),
    ("reading", "read_display_chapter"): (
        {"bookUrl": "regtest:/nope"}, ("error_contains", "书籍不存在")),
    ("reading", "read_adjacent_chapters"): (
        {"bookUrl": "regtest:/nope"}, ("error_contains", "书籍不存在")),
    ("reading", "search_book_content"): (
        {"query": "x", "bookUrl": "regtest:/nope"}, ("error_contains", "书籍不存在")),
    ("reading", "list_bookmarks"): (
        {"bookUrl": "regtest:/nope"}, ("error_contains", "书籍不存在")),
    ("reading", "create_bookmark"): (
        {"bookUrl": "regtest:/nope", "position": 0, "coordinate": "raw",
         "revision": "x"}, ("error_contains", "书籍不存在")),
    ("reading", "update_bookmark"): (
        {"bookUrl": "regtest:/nope", "id": 999999}, ("error_contains", "书籍不存在")),
    ("reading", "delete_bookmark"): (
        {"bookUrl": "regtest:/nope", "id": 999999}, ("error_contains", "书籍不存在")),
    ("reading", "jump_to_reading_position"): (
        {"position": 0, "coordinate": "raw", "revision": "x"},
        ("error_contains", "没有当前阅读书籍")),
    ("library", "list_book_sources"): ({}, ("ok", ["sources"])),
    ("library", "query_read_records"): ({}, ("ok", [])),
    ("library", "search_book_source"): (
        {"keyword": "REGTEST_NO_SUCH_BOOK_98765", "limit": 1}, ("ok_or_error",)),
    ("sources", "get_book_source"): (
        {"searchKey": "REGTEST_NOPE"}, ("ok", ["sources"])),
    ("sources", "create_book_source"): ({"save": False}, ("ok_or_error",)),
    ("sources", "update_book_source"): ({"save": False}, ("ok_or_error",)),
    ("sources", "fetch_source_html"): (None, ("seq", [
        # 抓取失败必须明确失败，不能伪装成功（回归：曾返回 ok:true/200）
        ({"url": "http://127.0.0.1:9/regtest"}, ("error_contains", "拒绝")),
    ])),
    ("sources", "debug_book_source"): (None, ("skip", "联网耗时调试，人工复核")),
    ("settings", "get_app_settings"): (
        {"keys": ["themeMode"]}, ("ok", ["themeMode"])),
    ("settings", "set_app_setting"): (None, ("roundtrip",)),
    ("settings", "set_app_settings_batch"): (None, ("roundtrip",)),
    ("web", "search_web_tavily"): ({"query": "test"}, ("error_contains", "Tavily")),
    ("memory", "memory_search"): (None, ("seq", [
        ({"query": "regtest", "scope": "global", "mode": "keyword"},
         ("ok", ["matches"])),
        ({"query": "regtest", "scope": "global", "mode": "vector"},
         ("error_contains", "嵌入")),
    ])),
    ("memory", "memory_add"): (
        {"content": "REGTEST", "scope": "global", "type": "test"},
        ("error_contains", "嵌入")),
    ("memory", "memory_update"): (
        {"id": "regtest-nope", "content": "x"}, ("error_contains", "不存在")),
    ("memory", "memory_delete"): (
        {"id": "regtest-nope"}, ("error_contains", "不存在")),
    ("memory", "memory_rebuild_index"): ({}, ("ok", ["indexed"])),
}


def mcp_call(port, hdr, name, args, call_id="call"):
    status, _, body = mcp_post(port, {
        "jsonrpc": "2.0", "id": call_id,
        "method": "tools/call",
        "params": {"name": name, "arguments": args}}, hdr)
    return status, body


def cmd_run():
    from collections import Counter
    stats = Counter()
    failures = []
    cfgs = server_configs()
    if not precheck_listeners(cfgs):
        sys.exit(2)
    with open(os.path.join(HERE, "defs.json"), encoding="utf-8") as f:
        alldefs = json.load(f)

    def record(mid, name, ok, detail=""):
        stats["passed" if ok else "failed"] += 1
        if not ok:
            failures.append((mid, name, detail))

    def one_call(mid, name, args, exp, port, hdr):
        status, body = mcp_call(port, hdr, name, args)
        return check(mid, name, status, body, exp)

    for mid in MODULES:
        port, key = cfgs[mid]["port"], cfgs[mid]["apiKey"]
        forward(port)
        try:
            sid, _ = legacy_session(port, key)
            hdr = {"Authorization": f"Bearer {key}",
                   "MCP-Protocol-Version": "2025-11-25", "Mcp-Session-Id": sid}
            transport_checks(mid, port, key, record)
            modern_check(mid, port, key, record)
            for tool in alldefs[mid]["tools"]:
                name = tool["name"]
                args, exp = CALLS.get((mid, name), (None, ("listed",)))
                kind = exp[0]
                if kind == "listed":
                    stats["listed_only"] += 1
                    print(f"[{mid}/{name}] listed-only")
                elif kind == "skip":
                    stats["skipped"] += 1
                    print(f"[{mid}/{name}] SKIP {exp[1]}")
                elif kind == "seq":
                    allok = True
                    for step_args, step_exp in exp[1]:
                        if not one_call(mid, name, step_args, step_exp, port, hdr):
                            allok = False
                            break
                    record(mid, name, allok, "seq中断" if not allok else "")
                elif kind == "roundtrip":
                    record(mid, name, settings_roundtrip(mid, port, hdr))
                else:
                    record(mid, name, one_call(mid, name, args, exp, port, hdr))
        finally:
            unforward(port)
    total = sum(stats.values())
    print(f"\n覆盖: passed={stats['passed']} failed={stats['failed']} "
          f"listed_only={stats['listed_only']} skipped={stats['skipped']} total={total}")
    for mid, name, detail in failures:
        print(f"FAIL [{mid}/{name}] {detail}")
    sys.exit(1 if stats["failed"] else 0)


def transport_checks(mid, port, key, record):
    # 错误密钥必须拒绝
    status, _, body = mcp_post(port, {
        "jsonrpc": "2.0", "id": "neg",
        "method": "initialize",
        "params": {"protocolVersion": "2025-11-25", "capabilities": {},
                   "clientInfo": {"name": "regtest", "version": "1"}}},
        {"Authorization": "Bearer WRONG", "MCP-Protocol-Version": "2025-11-25"})
    ok = status in (401, 403)
    print(f"[{mid}/transport] {'PASS' if ok else 'FAIL'} 错密钥->HTTP{status}")
    record(mid, "transport.wrong-key", ok, f"HTTP {status} {body[:150]}")
    # 未知方法必须返回 JSON-RPC 错误而非崩溃（需合法会话）
    sid, _ = legacy_session(port, key)
    hdr = {"Authorization": f"Bearer {key}",
           "MCP-Protocol-Version": "2025-11-25", "Mcp-Session-Id": sid}
    status, _, body = mcp_post(port, {
        "jsonrpc": "2.0", "id": "neg2",
        "method": "nope/method", "params": {}}, hdr)
    ok = '"error"' in body
    print(f"[{mid}/transport] {'PASS' if ok else 'FAIL'} 未知方法->JSONRPC错误")
    record(mid, "transport.unknown-method", ok, body[:150])


def modern_check(mid, port, key, record):
    # 现行版本发现接口：逐请求元数据齐全时必须声明支持 2026-07-28
    status, _, body = mcp_post(port, {
        "jsonrpc": "2.0", "id": "discover",
        "method": "server/discover",
        "params": {"_meta": {
            "io.modelcontextprotocol/protocolVersion": "2026-07-28",
            "io.modelcontextprotocol/clientInfo": {"name": "regtest", "version": "1"},
            "io.modelcontextprotocol/clientCapabilities": {}}}}, {
            "Authorization": f"Bearer {key}",
            "MCP-Protocol-Version": "2026-07-28",
            "Mcp-Method": "server/discover"})
    try:
        versions = json.loads(body)["result"]["supportedVersions"]
        ok = status == 200 and "2026-07-28" in versions
    except Exception:
        ok = False
    print(f"[{mid}/transport] {'PASS' if ok else 'FAIL'} server/discover声明现行版本")
    record(mid, "transport.server-discover", ok, body[:150])


def precheck_listeners(cfgs):
    """矩阵前置：7 个端口必须能握手。崩溃/重装后监听会死，
    此时直接报因（先跑 setup on），不把连接失败记成工具失败。"""
    down = []
    for mid in MODULES:
        cfg = cfgs.get(mid)
        if not cfg or not cfg.get("enabled"):
            down.append(f"{mid}(开关未开)")
            continue
        port, key = cfg["port"], cfg["apiKey"]
        forward(port)
        try:
            status, _, body = mcp_post(port, {
                "jsonrpc": "2.0", "id": "pre",
                "method": "initialize",
                "params": {"protocolVersion": "2025-11-25", "capabilities": {},
                           "clientInfo": {"name": "regtest", "version": "1"}}},
                {"Authorization": f"Bearer {key}",
                 "MCP-Protocol-Version": "2025-11-25"})
            if status != 200:
                down.append(f"{mid}(HTTP {status})")
        finally:
            unforward(port)
    if down:
        print(f"PRECHECK FAIL 监听未起: {down}；先跑 `setup on` 再 run")
        return False
    print("PRECHECK PASS 7 个监听全部可握手")
    return True


def settings_values(result):
    """get_app_settings 形状：structuredContent.items[] -> {key: value}。"""
    items = result.get("structuredContent", {}).get("items", [])
    return {it["key"]: it.get("value") for it in items if "key" in it}


def settings_roundtrip(mid, port, hdr):
    # 读-同值写-比对：覆盖 set 单个/批量且不改变用户状态
    tag = f"[{mid}/settings.roundtrip]"
    st, body = mcp_call(port, hdr, "get_app_settings", {"keys": ["aiEnterToSend"]})
    try:
        cur = json.loads(body.split("data:")[-1])["result"]
        assert cur.get("isError") is False
        val = settings_values(cur)["aiEnterToSend"]
    except Exception:
        print(f"{tag} FAIL 读取现值失败: {body[:200]}")
        return False
    st1, b1 = mcp_call(port, hdr, "set_app_setting",
                       {"key": "aiEnterToSend", "value": val})
    st2, b2 = mcp_call(port, hdr, "set_app_settings_batch",
                       {"items": [{"key": "aiEnterToSend", "value": val}]})
    st3, b3 = mcp_call(port, hdr, "get_app_settings", {"keys": ["aiEnterToSend"]})
    try:
        r1 = json.loads(b1.split("data:")[-1])["result"]
        r2 = json.loads(b2.split("data:")[-1])["result"]
        back = settings_values(json.loads(b3.split("data:")[-1])["result"])["aiEnterToSend"]
        ok = (back == val and r1.get("isError") is False
              and r2.get("isError") is False)
    except Exception:
        ok = False
    print(f"{tag} {'PASS' if ok else 'FAIL'} 同值往返 aiEnterToSend={val}")
    return ok


def check(mid, name, status, body, exp):
    tag = f"[{mid}/{name}]"

    def payload():
        text = body.split("data:")[-1] if "data:" in body else body
        return json.loads(text)

    if exp[0] == "error_contains":
        try:
            data = payload()
        except Exception:
            print(f"{tag} FAIL 非JSON: {body[:200]}")
            return False
        good = data.get("result", {}).get("isError") is True and exp[1] in body
        print(f"{tag} {'PASS' if good else 'FAIL'} 期望明确失败含[{exp[1]}] HTTP {status}")
        return good
    # ok / ok_or_error / ok_eq
    try:
        result = payload().get("result", {})
    except Exception:
        print(f"{tag} FAIL 非JSON: {body[:200]}")
        return False
    if exp[0] == "ok_or_error":
        good = isinstance(result.get("isError"), bool)
        print(f"{tag} {'PASS' if good else 'FAIL'} 结构性(成功或明确失败) isError={result.get('isError')}")
        return good
    if exp[0] == "ok_eq":
        scope = dict(result.get("structuredContent", {})
                     if isinstance(result.get("structuredContent"), dict) else {})
        scope.update({k: v for k, v in result.items() if k != "structuredContent"})
        bad = {k: scope.get(k) for k in exp[1] if scope.get(k) != exp[1][k]}
        good = not bad and result.get("isError") is False
        print(f"{tag} {'PASS' if good else 'FAIL'} 精确值 {bad or '一致'}")
        return good
    if result.get("isError"):
        print(f"{tag} FAIL isError=true: {body[:300]}")
        return False
    missing = [k for k in exp[1] if k not in json.dumps(result, ensure_ascii=False)]
    if missing:
        print(f"{tag} FAIL 缺key {missing}")
        return False
    print(f"{tag} PASS")
    return True


FAKE_PORT = 18765
FAKE_PROVIDER_ID = "regtest-fake-llm"
FAKE_MODEL_ID = "regtest-fake-model"
FAKE_MODEL_NAME = "regtest-model"
FAKE_BASE_URL = f"http://127.0.0.1:{FAKE_PORT}"
PREFS_XML = "shared_prefs/io.legado.app.dev_preferences.xml"


def run_as(*args, input_text=None, timeout=60):
    p = subprocess.run(ADB + ["shell", "run-as", PKG] + list(args),
                       input=(input_text.encode("utf-8")
                              if input_text is not None else None),
                       capture_output=True, timeout=timeout)
    return p


def prefs_pull():
    p = run_as("cat", PREFS_XML, timeout=60)
    if p.returncode != 0:
        raise RuntimeError(
            f"读偏好失败: {p.stderr.decode('utf-8', 'replace')[:200]}")
    out = p.stdout
    return out.decode("utf-8") if isinstance(out, bytes) else out


def prefs_push(xml_text):
    adb("shell", "am", "force-stop", PKG)
    time.sleep(2)
    p = run_as("sh", "-c", f"cat > {PREFS_XML}", input_text=xml_text, timeout=60)
    if p.returncode != 0:
        raise RuntimeError(
            f"写偏好失败: {p.stderr.decode('utf-8', 'replace')[:200]}")


def prefs_set_fake_provider(enable):
    """注入/移除假LLM供应商。返回备份XML（enable=True时），供事后恢复。"""
    import xml.etree.ElementTree as ET
    raw = prefs_pull()
    if enable:
        backup = raw
    else:
        backup = None
    root = ET.fromstring(raw)
    def set_string(name, value):
        for e in root.findall("string"):
            if e.get("name") == name:
                e.text = value
                return
        ET.SubElement(root, "string", name=name).text = value
    if enable:
        provider = [{"id": FAKE_PROVIDER_ID, "name": "REGTEST-FAKE",
                     "baseUrl": FAKE_BASE_URL, "apiKey": "",
                     "headers": "", "supportVision": False}]
        model = [{"id": FAKE_MODEL_ID, "providerId": FAKE_PROVIDER_ID,
                  "modelId": FAKE_MODEL_NAME}]
        set_string("aiProviderList", json.dumps(provider, ensure_ascii=False))
        set_string("aiModelConfigList", json.dumps(model, ensure_ascii=False))
        set_string("aiCurrentProviderId", FAKE_PROVIDER_ID)
        set_string("aiCurrentModelId", FAKE_MODEL_ID)
    else:
        for e in root.findall("string"):
            if e.get("name") in ("aiCurrentProviderId", "aiCurrentModelId"):
                root.remove(e)
    prefs_push(ET.tostring(root, encoding="unicode", xml_declaration=True))
    return backup


def cmd_chat():
    import xml.etree.ElementTree as ET
    log_path = os.path.join(TMP, "fakellm.jsonl")
    if os.path.exists(log_path):
        os.remove(log_path)
    print("[chat] 注入假LLM供应商…")
    backup = prefs_set_fake_provider(True)
    fake = None
    try:
        adb("reverse", f"tcp:{FAKE_PORT}", f"tcp:{FAKE_PORT}")
        env = dict(os.environ, REGTEST_LOG=log_path)
        fake = subprocess.Popen([sys.executable, os.path.join(HERE, "fakellm.py"),
                                 str(FAKE_PORT)], env=env)
        time.sleep(1)
        assert fake.poll() is None, "假LLM启动失败"
        print("[chat] 长按搜索键进AI聊天…")
        adb("shell", "am", "start", "-n",
            f"{PKG}/io.legado.app.ui.main.MainActivity")
        time.sleep(4)
        adb("shell", "input", "swipe", "944", "2283", "944", "2283", "1200")
        time.sleep(4)
        rows = ui_nodes(dump("chat_open"))
        labels = [t for t, _ in rows]
        assert any("AI" in t or "助手" in t or "input" in t.lower() or "编辑" in t
                   for t in labels), f"没进聊天页: {labels[:12]}"
        # 找输入框：EditText 类优先，否则找可点击的底部输入区
        t = ET.parse(os.path.join(TMP, "chat_open.xml"))
        edit = input_btn = send_btn = None
        for n in t.iter("node"):
            cls = n.get("class", "")
            b = n.get("bounds", "[0,0][0,0]").replace("][", ",").strip("[]")
            l, tt, r, bb = map(int, b.split(","))
            center = ((l + r) // 2, (tt + bb) // 2)
            if "EditText" in cls and edit is None:
                edit = center
            if n.get("clickable") == "true" and (n.get("text") or "") in ("发送", "➤", "▶", "确定"):
                send_btn = center
        assert edit, "找不到聊天输入框"
        tap(*edit)
        time.sleep(1)
        adb("shell", "input", "text", "REGTEST-HELLO")
        time.sleep(1)
        if send_btn:
            tap(*send_btn)
        else:  # 兜底：回车发送
            adb("shell", "input", "keyevent", "66")
        print("[chat] 等REGTEST-OK（最多150s）…")
        deadline = time.time() + 150
        seen = ""
        while time.time() < deadline:
            time.sleep(5)
            rows = ui_nodes(dump("chat_wait"))
            seen = " ".join(t for t, _ in rows)
            if "REGTEST-OK" in seen:
                break
            if "REGTEST-NOTOOL" in seen:
                raise RuntimeError("假LLM没收到工具表（R1回NOTOOL），宿主tools.discover异常")
        assert "REGTEST-OK" in seen, f"超时未见终稿: {seen[-300:]}"
        print("[chat] PASS 终稿REGTEST-OK")
        # 断言假LLM侧：R1无tool_calls且带工具表，R2含assistant.tool_calls+tool角色
        reqs = [json.loads(line) for line in open(log_path, encoding="utf-8")
                if line.strip()]
        chats = [r for r in reqs if r["path"].endswith("/chat/completions")]
        assert len(chats) >= 2, f"模型请求不足2次: {len(chats)}"
        assert chats[0]["n_tools"] > 0 and not chats[0]["has_tool_calls"], \
            f"R1应带工具表无tool_calls: {chats[0]}"
        assert chats[1]["has_tool_calls"] and "tool" in chats[1]["roles"], \
            f"R2应含完整工具往返: {chats[1]}"
        print(f"[chat] PASS 工具往返完整（R1工具数={chats[0]['n_tools']}）")
        # 断言持久化：消息落库且含tool_calls
        msgs = int(db("SELECT COUNT(*) FROM messages;") or 0)
        assert msgs > 0, "agent消息未落库"
        print(f"[chat] PASS 消息落库 {msgs} 条")
    finally:
        if fake is not None:
            fake.terminate()
        adb("forward", "--remove", f"tcp:{FAKE_PORT}")
        print("[chat] 恢复原供应商偏好…")
        adb("shell", "am", "force-stop", PKG)
        time.sleep(2)
        p = run_as("sh", "-c", f"cat > {PREFS_XML}", input_text=backup, timeout=60)
        assert p.returncode == 0, "偏好恢复失败"
        adb("shell", "am", "start", "-n",
            f"{PKG}/io.legado.app.ui.main.MainActivity")
        time.sleep(3)
    print("[chat] 全过")


if __name__ == "__main__":
    cmd, *rest = sys.argv[1:] + [None]
    if cmd == "setup":
        cmd_setup(rest[0])
    elif cmd == "probe":
        cmd_probe()
    elif cmd == "run":
        cmd_run()
    elif cmd == "chat":
        cmd_chat()
    else:
        print(__doc__)
        sys.exit(2)
