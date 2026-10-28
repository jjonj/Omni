# Current service recovery (Rust production, 2026-10-18)

## Recovery update 2026-10-22 (supersedes older MCP port references below)

Rust OMCP is restored to its fixed original **8772**, with browser extension bridge **18773**. A live Python process had inherited the dead sidecar's socket handles; closing only those verified handles reclaimed both ports without reboot or killing Python. The watchdog now automates inherited-handle reclamation instead of moving ports. The 8772/18773 pair was left bound to a terminated process on 2026-10-21; the temporary 8774/18775 workaround was retired after the actual inherited-handle holder was found. `%LOCALAPPDATA%\OmniHub\mcp-sidecar\ports.json` now records the fixed original pair.
The prior 8770/18772 listeners were left reserved against a terminated process
after a failed upgrade; simply reopening the desktop Hub cannot restart its
independent MCP sidecar. Tailscale Funnel `/mcp` targets Lite on 8771; the auth gate on 8791 is independent of the Rust sidecar port.
`OmniHub Rust Watchdog` now checks both Rust tasks every minute and at logon
using real API/Gateway/MCP requests, kernel-level process liveness (not WMI),
forced cleanup of a hung verified owner and targeted inherited socket-handle
reclamation on the original ports. It recovers after three failed passes.
The watchdog itself has a 45-second worker-tree timeout and a kill-on-close
Windows job, so a hung pass or terminated runner cannot leave workers behind.
Task Scheduler retains a 55-second outer limit; its independent minute trigger
replaces failed or timed-out passes. Three one-minute failure retries are also
configured, but process-exit recovery relies on the verified repeating trigger.
Pause with `%LOCALAPPDATA%\OmniHub\watchdog\pause.txt` during maintenance (expires after 30 minutes; disable the watchdog task for longer work).
Authoritative procedure, diagnostics, and install/test scripts:
`D:\SSDProjects\Tools\Omnish\main\docs\OMNIHUB_RECOVERY.md`.
Legacy C# watchdog remains disabled; do not re-enable it.

## Ownership
- `OmniHub Rust`: live Rust GUI Hub; API/SignalR port **5000**, Gateway **3333**, Google Home **5055**. On 2026-10-22, the approved GUI upgrade shipped OMCP down/recovery notifications. Retained dead wildcard bindings are bypassed with direct listeners on current loopback/LAN/Tailscale IPv4 interfaces; ports and client peer addresses are preserved. See `OMNIHUB_RECOVERY.md` in Omnish.
  Launcher: `D:\SSDProjects\Tools\Omnish\main\scripts\omnihub-launch-production.ps1`.
- `OmniHub Rust MCP`: separate Rust sidecar at loopback **8772** (bridge **18773**), started with
  `scripts/omnihub-launch-mcp.ps1` in the Omnish repository.
- `OmniMCP-Lite`: standalone MCP **8771**. Never restart it just to diagnose Rust. Its task now uses `scripts/services/omnimcp_lite_hidden.vbs` through `wscript.exe //B //Nologo`, preserving its current executable. The live console was hidden without restart; its watchdog accepts the exact hidden wrapper for recovery.
- `OmniMCP-Lite Watchdog`: Python supervisor at
  `D:\SSDProjects\Omni\scripts\services\omnimcp_lite_watchdog.py`;
  state and recovery logs live in `%LOCALAPPDATA%\OmniSync\lite-watchdog\`.
- Legacy `OmniSync Hub` and `OmniHub Watchdog` tasks must remain DISABLED;
  they target retired C# source.

## Safe health checks
`Get-ScheduledTask` for these tasks; inspect actual listener ownership with
`Get-NetTCPConnection -State Listen` for 3333/5000/8772/8771.
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
**OmniHub desktop release safety:** Never deploy an image built with plain
`cargo build --release` (it attempts the offline localhost:1430 dev server).
Use `D:\SSDProjects\Tools\Omnish\main\scripts\omnihub-stage-production.ps1`
for a Tauri production build + asset verification; `-Activate` only selects the
image for the NEXT restart. The scheduled launcher verifies embedded Vite assets
and SHA-256 from `%LOCALAPPDATA%\OmniHub\production\active-release.json`,
falling back to the prior checked-good image if needed. Verify without a restart:
`omnihub-launch-production.ps1 -ValidateOnly` and
`test-omnihub-release-guard.ps1`. Full procedure:
`D:\SSDProjects\Tools\Omnish\main\docs\OMNIHUB_PRODUCTION_BUILD.md`.
Keep separate auth configuration, running ports, Funnel/tunnel routes and Task
Scheduler action/identity unchanged. Check actual WebView rendering after any
coordinated deployment; API/Gateway health alone does not prove UI health.
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


ChatGPT's authenticated OMCP route is the separate custom-domain OAuth gateway,
not Funnel `/mcp`. Its task is `Rancorous OmniMCP Auth Gate` (8793) in
`D:/SSDProjects/Tools/rancorous-deployment-prep`. Private `mcp-upstream.json`
selects Rust and explicitly names the Hub user's `ports.json`; this is required
because the gateway runs as SYSTEM. Public and connector command/session calls
were verified after restoration to 8772. OAuth credentials were preserved.
