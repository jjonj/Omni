# Retired C# MCP implementation

The old in-process C# OmniHub MCP server and `OmniSync.Hub` project were
removed from the Omni Git checkout on 2026-10-18. **Port 8770 is no longer
a retired endpoint:** it is now owned by the independent **Rust OmniHub MCP**
sidecar, running from `D:\SSDProjects\Tools\Omnish\main` under the
`OmniHub Rust MCP` scheduled task.

The other independent server, **OmniMCP-Lite**, remains at loopback port 8771
and is supervised by `scripts/services/omnimcp_lite_watchdog.py`.
Do not re-enable old C# tasks or assume MCP requests are forwarded between
Rust and Lite. These are separate implementations.

See `docs/SERVICE_RECOVERY.md` and `docs/MCP_SERVER.md`.
