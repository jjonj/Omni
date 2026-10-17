"""Smoke test for the OmniHub MCP endpoint. The Hub must be running with Mcp:Enabled.

Usage: python test_hub_mcp.py [http://127.0.0.1:8770/mcp]
"""
import json
import os
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.request

URL = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8770/mcp"
failures = []
_next_id = 0


def rpc(method, params=None, notify=False):
    global _next_id
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if not notify:
        _next_id += 1
        msg["id"] = _next_id
    req = urllib.request.Request(
        URL,
        data=json.dumps(msg).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"HTTP {e.code} for {method}: {e.read()[:300]!r}")


def call(name, args):
    reply = rpc("tools/call", {"name": name, "arguments": args})
    if "error" in reply:
        raise AssertionError(f"{name} protocol error: {reply['error']}")
    res = reply["result"]
    text = res["content"][0]["text"]
    if res.get("isError"):
        raise AssertionError(f"{name} failed: {text}")
    return text


def check(label, cond):
    print(("PASS " if cond else "FAIL ") + label)
    if not cond:
        failures.append(label)


init = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "0"}})
check("initialize advertises tools", "tools" in init["result"]["capabilities"])
rpc("notifications/initialized", notify=True)

names = {t["name"] for t in rpc("tools/list")["result"]["tools"]}
check("tools/list has core tools", {"run_command", "read_file", "write_file", "edit_file", "search_files", "kill_process"} <= names)

check("run_command cmd echo", "omni-mcp-ok" in call("run_command", {"command": "echo omni-mcp-ok", "shell": "cmd"}))
check("run_command powershell", "ps-ok" in call("run_command", {"command": "Write-Output 'ps-ok'"}))

workdir = tempfile.mkdtemp(prefix="omni_mcp_test_")
try:
    path = os.path.join(workdir, "sample.txt")
    call("write_file", {"path": path, "content": "alpha\r\nbeta\r\n"})
    call("edit_file", {"path": path, "edits": [{"old_string": "beta\n", "new_string": "gamma\n"}]})
    text = call("read_file", {"path": path})
    check("edit_file keeps CRLF and replaces", "gamma" in text and "beta" not in text)
    check("search_files finds edited line", "gamma" in call("search_files", {"path": workdir, "pattern": "gamma"}))
    check("list_directory shows file", "sample.txt" in call("list_directory", {"path": workdir}))
    call("delete_path", {"path": path})
    check("delete_path removed file", not os.path.exists(path))
finally:
    shutil.rmtree(workdir, ignore_errors=True)

started = call("start_process", {"command": "Write-Output bg-start; Start-Sleep -Seconds 30"})
proc_id = started.split("id=")[1].split()[0]
time.sleep(2)
check("background output readable", "bg-start" in call("read_process_output", {"id": proc_id}))
call("stop_process", {"id": proc_id})

check("run_command reports timeout", "timed_out: true" in call("run_command", {"command": "Start-Sleep -Seconds 10", "timeout_seconds": 2}))

original_clipboard = call("get_clipboard", {})
call("set_clipboard", {"text": "omni-mcp-clipboard-test"})
check("clipboard round trip", call("get_clipboard", {}) == "omni-mcp-clipboard-test")
if original_clipboard != "clipboard does not contain text":
    call("set_clipboard", {"text": original_clipboard})

shot = rpc("tools/call", {"name": "screenshot", "arguments": {"scale": 0.3}})
shot_content = shot["result"]["content"]
image_parts = [c for c in shot_content if c["type"] == "image"]
check("screenshot returns JPEG image content", bool(image_parts) and image_parts[0]["mimeType"] == "image/jpeg"
      and image_parts[0]["data"].startswith("/9j/"))

print(f"\n{len(failures)} failure(s)")
sys.exit(1 if failures else 0)
