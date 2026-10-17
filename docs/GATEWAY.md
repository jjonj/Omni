# Rust OmniHub Gateway

Production Gateway: `http://127.0.0.1:3333/gateway/`.
Production API/SignalR: `http://127.0.0.1:5000/` and `/signalrhub`.
Google Home listener: 5055.

**Owner:** `D:\SSDProjects\Tools\Omnish\main`, a separate Rust repository.
Scheduled task: `OmniHub Rust` invoking
`scripts/omnihub-launch-production.ps1` from that repository.
The old `OmniSync.Hub/docs/GATEWAY.md` and C# controllers were removed.

The OmniSync Web client still contains `OmniSync.Web/www/gateway/` for
historical/UI integration. When changing a shared page, keep the actual
Rust-owned assets and backend route contract in sync. No C# counterpart exists.

Web tool registration is managed through the independent OmniMCP-Lite
`register_web_tool` / `list_web_tools` tools. The user-visible
`/gateway/launch.html?tool=<slug>` requires an actual registered tool.
Do not directly edit the live `%APPDATA%\OmniSync\gateway-tools.json`
while Rust Hub is running.
