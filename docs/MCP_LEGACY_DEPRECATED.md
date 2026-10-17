# DEPRECATED — OmniHub-hosted MCP server (8770)

**Status:** Deprecated as of 2026-10-14. Do not re-enable, extend, or deploy this server.
It is retained temporarily to allow clean removal later.

The old MCP implementation is in `OmniSync.Hub/src/OmniSync.Hub/Logic/Services/Mcp/`
(`McpEndpoint`, `McpTools`, `McpDesktopTools`, `McpWindowTools`).
It remains in the OmniHub process source for compatibility only.
Both source and deployed OmniHub `appsettings.json` have `Mcp.Enabled=false`.
Normal OmniHub services on ports 5000 and 3333 are unaffected.

**Replacement:** `D:\SSDProjects\Tools\McpHelloProbe` (OmniMCP-Lite v0.5.0+),
hosted independently on `127.0.0.1:8771` via the `OmniMCP-Lite` Windows
scheduled task. New MCP features must be developed there. All existing 47
tools are served in-process by Lite, including UI Automation, Win32 window
management, virtual desktops, screenshots, mouse/cursor actions, process/file
tools, and ChatGPT subagent spawning. No request from Lite is forwarded to
`127.0.0.1:8770`.

**OmniHub watchdog:** `OmniMcpLiteWatchdogService` runs inside OmniHub as a
hosted background service (enabled by `OmniMcpLiteWatchdog:Enabled`, default
`true`). It checks `http://127.0.0.1:8771/health` every 10 seconds and, after
three consecutive failed checks, restarts the independent `OmniMCP-Lite`
Windows scheduled task via `schtasks /End` and `/Run`. Restart attempts are
limited to once per 60 seconds. This respects the task's versioned executable,
elevation, and logon-session configuration, and does not launch a hardcoded
executable or re-enable Hub MCP. It logs failures and recovery to OmniHub.
The scheduled task must exist and be enabled; the watchdog does not recreate
it if it is removed. The independent server continues running after Hub exits.

**Future removal checklist:** Check that all client configurations refer to
Lite; remove deprecated Hub MCP service registration, endpoint, options and
tool files; leave the other Hub APIs intact. Do not delete this code as part
of the 2026-10-14 migration.

**Security note:** The current Tailscale Funnel publicly exposes privileged
MCP tools. Ensure a proper access-control mechanism before depending on this
endpoint for unattended public access.
