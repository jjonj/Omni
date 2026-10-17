# Current service recovery (Rust production, 2026-10-18)

## Ownership
- `OmniHub Rust`: live Rust GUI Hub; API/SignalR port **5000**, Gateway **3333**, Google Home **5055**.
  Launcher: `D:\SSDProjects\Tools\Omnish\main\scripts\omnihub-launch-production.ps1`.
- `OmniHub Rust MCP`: separate Rust sidecar at loopback **8770**, started with
  `scripts/omnihub-launch-mcp.ps1` in the Omnish repository.
- `OmniMCP-Lite`: standalone MCP **8771**. Never restart it just to diagnose Rust.
- `OmniMCP-Lite Watchdog`: Python supervisor at
  `D:\SSDProjects\Omni\scripts\services\omnimcp_lite_watchdog.py`;
  state and recovery logs live in `%LOCALAPPDATA%\OmniSync\lite-watchdog\`.
- Legacy `OmniSync Hub` and `OmniHub Watchdog` tasks must remain DISABLED;
  they target retired C# source.

## Safe health checks
`Get-ScheduledTask` for these tasks; inspect actual listener ownership with
`Get-NetTCPConnection -State Listen` for 3333/5000/8770/8771.
Use an independent HTTP GET of `http://127.0.0.1:3333/gateway/` and
a real MCP initialize/tool call to each MCP implementation.
A process being present does not establish request health.
Keep live tokens, public authentication endpoints and credentials out of logs.

The Rust Hub config is `%LOCALAPPDATA%\OmniHub\config\appsettings.json`.
The live process may retain its former C# path in its launch environment until its
**next ordinary Rust restart**. A local ignored compatibility file at
`OmniSync.Hub/src/OmniSync.Hub/appsettings.json` temporarily preserves that path;
do not delete it without verifying the live process no longer uses it.

Avoid restarting production Hub or either MCP during routine source maintenance.
To change Rust startup, edit the Omnish launcher *without running it*; changes
apply on the next normal scheduled start. Keep separate auth configuration,
running ports, Funnel/tunnel routes and Task Scheduler action/identity unchanged.
`OmniMCP-Lite Watchdog` can be restarted independently from MCP servers when
its script location changes; verify listener PIDs are stable afterward.

## Private config and recovery files
Do not check secrets into Git. The active private config is under LocalAppData,
and the retirement backups (including the old task XML and original C# config)
are under `%LOCALAPPDATA%\OmniHub\legacy-hub-retirement-20261018\`.
The complete retired project is archived outside this Git checkout at
`D:\SSDProjects\Omni-LegacyCSharpHub-archive-20261018`.
The user's setup JSON and gateway tool registrations remain in AppData.
No Rust Hub source or executable was deleted.
