#!/usr/bin/env python3
"""PhaseC/PhaseD E2E 冒烟 mock LLM 服务器（tools/smoke.sh 使用）。

按 --mode 模拟 LLM 行为：
  echo      成功：直接返回最终回复（附带打印 system prompt，可验证注入防护段）
  fail      恒 500：验证 LLM_UNAVAILABLE 错误码
  tool      首轮返回 shell tool_call（耗时命令触发工具超时兜底），二轮返回最终回复
  anthropic 按 Anthropic /v1/messages 协议返回成功响应
  gemini    按 Gemini generateContent 协议返回成功响应

用法: python3 mock_llm.py --port 19996 --mode echo
"""
import argparse
import json
from http.server import HTTPServer, BaseHTTPRequestHandler


def build_resp(model, content, tool_calls=None, finish_reason="stop"):
    msg = {"role": "assistant", "content": content}
    if tool_calls:
        msg["tool_calls"] = tool_calls
    return {
        "id": "chatcmpl-smoke", "object": "chat.completion", "model": model,
        "choices": [{"index": 0, "message": msg, "finish_reason": finish_reason}],
        "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
    }


def build_anthropic_resp(model, content):
    return {
        "id": "msg_smoke", "type": "message", "role": "assistant", "model": model,
        "content": [{"type": "text", "text": content}],
        "stop_reason": "end_turn", "stop_sequence": None,
        "usage": {"input_tokens": 10, "output_tokens": 10},
    }


def build_gemini_resp(content):
    return {
        "candidates": [{"content": {"parts": [{"text": content}]}, "finishReason": "STOP"}],
        "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 10},
    }


class H(BaseHTTPRequestHandler):
    mode = "echo"
    model = "mock-model"

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length) or b'{}')
        messages = body.get('messages', [])
        sys_prompt = (body.get('system', '')
                      or (body.get('systemInstruction') or {}).get('parts', [{}])[0].get('text', '')
                      or (messages[0].get('content', '') if messages else ''))
        has_tool = any(m.get('role') == 'tool' for m in messages)
        print("[mock:%s] sys_prompt_len=%d has_tool=%s"
              % (H.mode, len(sys_prompt), has_tool), flush=True)
        if H.mode == "fail":
            data = json.dumps({"error": {"message": "mock 500", "type": "server_error"}},
                              ensure_ascii=False).encode('utf-8')
            self.send_response(500)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if H.mode == "anthropic":
            resp = build_anthropic_resp(H.model, "冒烟场景通过: anthropic")
        elif H.mode == "gemini":
            resp = build_gemini_resp("冒烟场景通过: gemini")
        elif H.mode == "tool" and not has_tool:
            resp = build_resp(H.model, None, [{
                "id": "call_smoke", "type": "function",
                "function": {"name": "shell",
                             "arguments": "{\"command\": \"curl -m 8 http://192.0.2.1/\"}"}}],
                finish_reason="tool_calls")
        else:
            resp = build_resp(H.model, "冒烟场景通过: " + H.mode)
        data = json.dumps(resp, ensure_ascii=False).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        print("[mock] " + fmt % args, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=19996)
    parser.add_argument('--mode', choices=['echo', 'fail', 'tool', 'anthropic', 'gemini'],
                        default='echo')
    args = parser.parse_args()
    H.mode = args.mode
    HTTPServer(('127.0.0.1', args.port), H).serve_forever()
