# OmniHub MCP server

OmniHub runs a Model Context Protocol (MCP) server so AI clients such as Claude Code, Codex and ChatGPT can run commands, manage processes, read and edit files, take screenshots and use the clipboard on this PC. It has full access to the machine by design. Security and permissions are out of scope for this project.

## Overview

| Item | Value |
| --- | --- |
| Transport | MCP Streamable HTTP, JSON-RPC 2.0 over POST |
| Default local URL | `http://127.0.0.1:8770/mcp` |
| Protocol versions | `2025-11-25`, `2025-06-18`, `2025-03-26` |
| Implementation | Hand-written in `OmniSync.Hub\src\OmniSync.Hub\Logic\Services\Mcp\` |
| Server name | `omnihub` |

The server is a separate Kestrel listener inside the Hub process. Only requests that arrive on the MCP port reach the MCP endpoint. Every other path on that port returns 404.

Protocol behaviour:

- Each reply is a plain `application/json` response. There is no server-sent event stream.
- `POST` carries requests, batches and notifications. Notifications return HTTP 202.
- `GET` returns 405. `DELETE` returns 200, since there are no sessions to close.
- `notifications/cancelled` cancels the in-flight request with the matching id.

## Configuration

`OmniSync.Hub\src\OmniSync.Hub\appsettings.json`:

```json
"Mcp": {
  "Enabled": true,
  "Host": "127.0.0.1",
  "Port": 8770
}
```

| Key | Meaning |
| --- | --- |
| `Enabled` | Set to `false` to turn the server off. |
| `Host` | `127.0.0.1` (or `localhost`) keeps it on loopback. Use `0.0.0.0` to listen on all interfaces. |
| `Port` | The MCP port. |

Startup behaviour (`Program.ResolveMcpOptions`):

- Ports 5000, 3333 and the Google Home port are reserved by the Hub and are rejected.
- If the port is already in use, the MCP server disables itself and logs `[MCP] MCP disabled: port N unavailable`. The Hub keeps running.
- Each MCP tool call is logged to the activity log as `[MCP] <tool> ok|failed in N ms`.

Port 8765 was in use by an unrelated process during setup, which is why the default is 8770. Check the port with `netstat -ano | findstr :8770` if the server is unreachable.

## Tools

The server exposes filesystem, process, desktop and window-management tools, including a ChatGPT-session launcher.

### Commands and processes

| Tool | Purpose | Main arguments |
| --- | --- | --- |
| `run_command` | Run a command and wait for it. Returns exit code, timeout status and output. | `command`, `shell` (`powershell` default, or `cmd`), `cwd`, `timeout_seconds` (default 45, max 600) |
| `spawn_chatgpt_subagent` | Open a named ChatGPT session in the already-running T3 Code desktop app and send its first prompt. Returns the session ID; fails if T3 Code is not running or the spawn request is rejected. | `title`, and exactly one of `prompt` or `prompt_file` (existing UTF-8 file path on the PC) |
| `start_process` | Start a long-running process in the background. Returns an id. | `command`, `shell`, `cwd` |
| `read_process_output` | Read output from a background process from an offset. | `id`, `offset` |
| `write_process_stdin` | Write text to a background process's stdin. | `id`, `text`, `newline` (default true) |
| `stop_process` | Stop a background process and its child processes. | `id` |
| `list_processes` | List running processes. | `name` (filter) |
| `kill_process` | Kill a process by id. | `pid`, `tree` (default true) |

Use `start_process` for anything longer than a few seconds, then poll with `read_process_output`. Background buffers hold the last 1 MB of output.

`spawn_chatgpt_subagent` wraps `D:\SSDProjects\Tools\t3code\scripts\spawn-chatgpt-session.mjs` using Node.js, passing arguments without a shell. T3 Code must already be running with its `chatgpt.spawn` desktop control pipe. The tool waits for message submission (up to 115 seconds), passes along failure diagnostics, and does not launch a second T3 Code instance. Restart the Hub and refresh the OmniMCP plugin tool list after deploying this new tool.

### Files and directories

| Tool | Purpose | Main arguments |
| --- | --- | --- |
| `read_file` | Read a text file with line numbers. | `path`, `offset`, `limit` (default 400, max 10000) |
| `write_file` | Create or overwrite a file. | `path`, `content`, `append` |
| `edit_file` | Replace exact text in a file. Keeps CRLF line endings. | `path`, `edits` (`old_string`, `new_string`, `replace_all`) |
| `list_directory` | List files and folders. | `path` |
| `search_files` | Regex search across files. | `path`, `pattern`, `glob`, `ignore_case`, `max_results` |
| `move_path` | Move or rename a file or folder. | `source`, `destination`, `overwrite` |
| `delete_path` | Delete a file or folder. | `path`, `recursive` |

Paths accept environment variables (`%USERPROFILE%`) and a leading `~` for the user profile.

### Screen and clipboard

| Tool | Purpose | Main arguments |
| --- | --- | --- |
| `screenshot` | Capture a screen as a JPEG image. | `monitor` (default 0), `scale` (0.1 to 1, default 0.5), `quality` (10 to 95, default 60) |
| `get_clipboard` | Read text from the clipboard. | none |
| `set_clipboard` | Put text on the clipboard. | `text` |

`screenshot` returns the image as MCP image content, so clients that support images show it inline.

## Connecting clients

### Claude Code (this PC)

Registered at user scope:

```powershell
claude mcp add --transport http --scope user omnihub http://127.0.0.1:8770/mcp
claude mcp list
```

`claude mcp list` should show `omnihub ... Connected`. Tools are loaded when a session starts, so an already-running session will not see the server. Start a new session to use it.

To test without an interactive session:

```powershell
claude -p "Use omnihub run_command to print the hostname" --allowedTools "mcp__omnihub"
```

### Codex

Add to `~/.codex/config.toml`:

```toml
[mcp_servers.omnihub]
url = "http://127.0.0.1:8770/mcp"
```

### ChatGPT on the web

ChatGPT's servers call the MCP endpoint from outside this network, so it needs a public HTTPS address. This PC uses Tailscale Funnel for that. Funnel publishes the path `/mcp` on the PC's `*.ts.net` address and forwards it to `http://127.0.0.1:8770/mcp`.

Find the public address with:

```powershell
& "E:\Program Files\Tailscale\tailscale.exe" funnel status
```

The line for `/mcp` gives the URL, which has the form `https://<machine>.<tailnet>.ts.net/mcp`. Do not commit that address to the repository. Anyone who has it can run commands on this PC.

Add the path if it is missing:

```powershell
& "E:\Program Files\Tailscale\tailscale.exe" funnel --bg --set-path=/mcp http://127.0.0.1:8770/mcp
```

Remove it:

```powershell
& "E:\Program Files\Tailscale\tailscale.exe" funnel --bg --set-path=/mcp http://127.0.0.1:8770/mcp off
```

Then in ChatGPT, on a Plus, Pro, Business, Enterprise or Edu account:

1. Go to **chatgpt.com/plugins**, click **+**, then **Add custom MCP server**. If the option is missing, turn on Developer Mode in Settings first. The menu labels have changed over time, so check the current ones.
2. Name it, for example `OmniHub`.
3. Server URL: the `https://…/mcp` address from `funnel status`.
4. Authentication: **No authentication**.
5. Tick the acknowledgement and click **Create as a plugin**.
6. Confirm the expected tool list is visible, including `spawn_chatgpt_subagent`.
7. Start a new chat and select the plugin.

After the server's tools change, open the plugin and click **Refresh**, then start a new chat.

## Testing

### Smoke test

With the Hub running:

```powershell
python D:\SSDProjects\Omni\TestScripts\McpServer\test_hub_mcp.py
```

It runs 12 checks: initialize, tool list, command execution, file edit and search, background processes, timeouts, clipboard round trip and a screenshot. Pass a different URL as the first argument to test another endpoint.

### Manual checks

```powershell
# Initialize
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8770/mcp -ContentType application/json -Body '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"manual","version":"1"}}}'

# Count tools
(Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8770/mcp -ContentType application/json -Body '{"jsonrpc":"2.0","id":2,"method":"tools/list"}').result.tools.Count
```

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `ECONNREFUSED` from a client | The Hub is not running or `Mcp:Enabled` is false. Start the Hub with `build_run_omnihub.py`. |
| Hub log says `MCP disabled: port N unavailable` | Another process holds the port. Find it with `netstat -ano`, or change `Mcp:Port`. |
| Claude Code shows omnihub as failed | Start a new session. Tools load at session start. |
| ChatGPT cannot connect | Check the Funnel path with `funnel status`. Test the public URL with a curl or `Invoke-RestMethod` request before blaming ChatGPT. |
| ChatGPT tool list is stale | Click **Refresh** on the plugin, then start a new chat. |
| Long `run_command` times out | Use `start_process` and `read_process_output` for work longer than the timeout. |

## Known gaps

- Child processes are not contained in a Job Object, so a killed Hub can leave processes running.
- There are no session ids. Requests are stateless.
- Concurrent writes to the same file are not locked.
- `search_files` is a managed implementation, not ripgrep.
- The ChatGPT connection has been set up but not verified live.

## Source map

| File | Role |
| --- | --- |
| `Logic\Services\Mcp\McpEndpoint.cs` | HTTP handling and JSON-RPC dispatch |
| `Logic\Services\Mcp\McpTools.cs` | Tool definitions and implementations |
| `Logic\Services\Mcp\ShellProcess.cs` | Background process wrapper with bounded output buffers |
| `Logic\Services\Mcp\McpOptions.cs` | Resolved configuration record |
| `Program.cs` | `ResolveMcpOptions`, Kestrel listener and routing |
| `Infrastructure\Services\ScreenshotService.cs` | Screen capture used by `screenshot` |
| `Infrastructure\Services\ClipboardService.cs` | Clipboard access used by the clipboard tools |
| `TestScripts\McpServer\test_hub_mcp.py` | Smoke test |
