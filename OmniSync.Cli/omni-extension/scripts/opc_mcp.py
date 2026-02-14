import sys
import json
import os
import subprocess
from pathlib import Path

# Unified OPC/Athena Project Path (Submodule)
OPC_ROOT = r"D:\SSDProjects\Omni\OmniSync.Assistant\Omni.Athena"

def log(msg):
    log_path = Path(os.path.dirname(__file__)).parent / "opc_mcp.log"
    with open(log_path, "a") as f:
        f.write(f"[{Path(__file__).name}] {msg}\n")
    sys.stderr.write(f"[OPC-MCP] {msg}\n")
    sys.stderr.flush()

def handle_search(query, limit=10, rerank=False):
    try:
        search_script = os.path.join(OPC_ROOT, "src", "athena", "tools", "search.py")
        cmd = [sys.executable, search_script, query, "--limit", str(limit), "--json"]
        if rerank:
            cmd.append("--rerank")

        env = os.environ.copy()
        env["PYTHONPATH"] = os.path.join(OPC_ROOT, "src")

        result = subprocess.run(cmd, capture_output=True, text=True, env=env)       
        if result.returncode == 0:
            return json.loads(result.stdout)
        else:
            log(f"Search subprocess error: {result.stderr}")
            return {"error": result.stderr}
    except Exception as e:
        log(f"Search exception: {str(e)}")
        return {"error": str(e)}

def handle_quicksave(summary):
    try:
        env = os.environ.copy()
        env["PYTHONPATH"] = os.path.join(OPC_ROOT, "src")
        cmd = [sys.executable, "-m", "athena", "save", summary]

        result = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=OPC_ROOT)
        return {"output": result.stdout, "success": result.returncode == 0}
    except Exception as e:
        log(f"Quicksave exception: {str(e)}")
        return {"error": str(e)}

def handle_sync():
    try:
        env = os.environ.copy()
        env["PYTHONPATH"] = os.path.join(OPC_ROOT, "src")
        cmd = [sys.executable, "-m", "athena", "opc", "sync"]

        result = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=os.getcwd())
        return {"output": result.stdout, "success": result.returncode == 0}
    except Exception as e:
        log(f"Sync exception: {str(e)}")
        return {"error": str(e)}

def main():
    log("Starting Unified OPC MCP Server...")

    while True:
        try:
            line = sys.stdin.readline()
            if not line:
                break

            req = json.loads(line)
            method = req.get("method")
            req_id = req.get("id")

            if method == "initialize":
                response = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {
                            "tools": {}
                        },
                        "serverInfo": {
                            "name": "opc-mcp",
                            "version": "1.0.0"
                        }
                    }
                }
            elif method in ["list_tools", "tools/list"]:
                response = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "tools": [
                            {
                                "name": "opc_search",
                                "description": "Search Athena's sovereign memory and project context",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "query": {"type": "string", "description": "The search query"},
                                        "limit": {"type": "integer", "default": 10},
                                        "rerank": {"type": "boolean", "default": False}
                                    },
                                    "required": ["query"]
                                }
                            },
                            {
                                "name": "opc_quicksave",
                                "description": "Save a checkpoint to the current session log",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {
                                        "summary": {"type": "string", "description": "Summary of the work done"}
                                    },
                                    "required": ["summary"]
                                }
                            },
                            {
                                "name": "opc_sync",
                                "description": "Synchronize codebase context and memory",
                                "inputSchema": {
                                    "type": "object",
                                    "properties": {}
                                }
                            }
                        ]
                    }
                }
            elif method in ["call_tool", "tools/call"]:
                params = req.get("params", {})
                name = params.get("name") or req.get("name")
                args = params.get("arguments") or req.get("arguments") or {}        

                if name == "opc_search":
                    result = handle_search(args.get("query"), args.get("limit", 10), args.get("rerank", False))
                    response = {
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "result": {"content": [{"type": "text", "text": json.dumps(result)}]}
                    }
                elif name == "opc_quicksave":
                    result = handle_quicksave(args.get("summary"))
                    response = {
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "result": {"content": [{"type": "text", "text": json.dumps(result)}]}
                    }
                elif name == "opc_sync":
                    result = handle_sync()
                    response = {
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "result": {"content": [{"type": "text", "text": json.dumps(result)}]}
                    }
                else:
                    response = {
                        "jsonrpc": "2.0",
                        "id": req_id,
                        "error": {"code": -32601, "message": f"Tool not found: {name}"}
                    }
            else:
                if req_id is not None:
                    response = {"jsonrpc": "2.0", "id": req_id, "result": {}}
                else:
                    continue

            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()

        except Exception as e:
            log(f"Error: {str(e)}")

if __name__ == "__main__":
    main()
