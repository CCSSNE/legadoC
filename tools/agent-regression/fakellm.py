#!/usr/bin/env python3
"""假 LLM（OpenAI 兼容子集）：供 Agent JS 循环端到端回归，无 quota 消耗。

行为（按会话内 messages 状态机）：
  R1（无 assistant tool_calls）：下发一次工具调用。
       工具名从请求体 tools[] 里按 description 后缀 [module/tool] 动态取，
       避开模型工具名哈希，保证与宿主映射一致。
  R2+（已有 tool_calls）：输出正文 REGTEST-OK。
  其它：embeddings 等一律 400。

请求日志写 $REGTEST_LOG（JSON lines），供断言“连续工具往返完整”。
只用标准库。
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = os.environ.get("REGTEST_LOG", os.path.join(
    os.path.dirname(os.path.abspath(__file__)), ".tmp", "fakellm.jsonl"))
WANT_MODULE = os.environ.get("REGTEST_TOOL_MODULE", "bookshelf")
WANT_TOOL = os.environ.get("REGTEST_TOOL", "query_bookshelf")
FINAL_TEXT = os.environ.get("REGTEST_FINAL", "REGTEST-OK")


def sse_chunk(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json"):
        raw = body if isinstance(body, bytes) else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        try:
            req = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            return self._send(400, '{"error":"bad json"}')
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps({
                "path": self.path,
                "model": req.get("model"),
                "n_messages": len(req.get("messages", [])),
                "roles": [m.get("role") for m in req.get("messages", [])],
                "has_tool_calls": any(m.get("tool_calls") for m in req.get("messages", [])),
                "n_tools": len(req.get("tools", [])),
            }, ensure_ascii=False) + "\n")
        if not self.path.endswith("/chat/completions"):
            return self._send(404, '{"error":"no such route"}')
        tools = req.get("tools", [])
        if not any(m.get("tool_calls") for m in req.get("messages", [])):
            name = ""
            for t in tools:
                fn = t.get("function", {})
                if fn.get("description", "").strip().endswith(
                        f"[{WANT_MODULE}/{WANT_TOOL}]"):
                    name = fn.get("name", "")
                    break
            if not name:
                return self._send(200, json.dumps({
                    "choices": [{"message": {
                        "role": "assistant",
                        "content": "REGTEST-NOTOOL",
                    }, "finish_reason": "stop"}]}))
            body = b"".join([
                sse_chunk({"choices": [{"index": 0, "delta": {
                    "tool_calls": [{"index": 0, "id": "call_reg1",
                                    "function": {"name": name,
                                                 "arguments": "{}"}}]}}]}),
                sse_chunk({"choices": [{"index": 0, "delta": {},
                                         "finish_reason": "tool_calls"}]}),
                b"data: [DONE]\n\n",
            ])
            return self._send(200, body, "text/event-stream")
        body = b"".join([
            sse_chunk({"choices": [{"index": 0,
                                     "delta": {"content": FINAL_TEXT}}]}),
            sse_chunk({"choices": [{"index": 0, "delta": {},
                                     "finish_reason": "stop"}]}),
            b"data: [DONE]\n\n",
        ])
        return self._send(200, body, "text/event-stream")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18765
    if os.path.exists(LOG):
        os.remove(LOG)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
