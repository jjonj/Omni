# OmniSync tests (Rust Hub production)

The production Hub, SignalR transport and MCP sidecar are maintained and tested in
`D:/SSDProjects/Tools/Omnish/main`. The C# `OmniSync.Hub` source and its
assembly-specific Windows harnesses were retired on 2026-10-18.

Run non-destructive checks from this checkout:

- `python -m unittest TestScripts/McpServer/test_lite_watchdog.py` — independent Lite supervisor (mocked recovery; no service restarts).
- `python TestScripts/Browser/diagnose_browser_control.py` — browser extension files and Rust Hub SignalR negotiation (read-only).
- `dotnet test OmniZones/tests/OmniZones.Core.Tests/OmniZones.Core.Tests.csproj` — standalone zone engine.
- `node OmniSync.Web/tests/gateway_ui_smoke.cjs` — Web gateway UI (if Node is available).
- `OmniSync.Android/gradlew.bat :app:compileDebugKotlin` — Android compilation (no deployment).

Some other System scripts are *integration* tests that operate on live macros,
input devices, external apps, downloads or remote services. Inspect side effects
before invoking them; they are not part of the safe default smoke suite.
For migration details and private config paths see `docs/LEGACY_HUB_RETIREMENT.md`.
