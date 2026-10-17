# C# OmniSync.Hub retirement — 2026-10-18

The old C# WPF/ASP.NET Core Hub source was removed from the Omni Git index;
the production Hub is Rust and lives in a **separate repository**:
`D:\SSDProjects\Tools\Omnish\main`.

## Preserved operational state
- Private authentication settings copied, hash-verified and ACL-restricted
  under `%LOCALAPPDATA%\OmniHub\config\appsettings.json`.
- Previous configuration and Task Scheduler XML exported to
  `%LOCALAPPDATA%\OmniHub\legacy-hub-retirement-20261018\`.
- User setups, macros, assistant runs, gateway tools and other runtime state
  remain in `%APPDATA%\OmniSync\` untouched.
- Local-only ACL-restricted archive of the C# project source, scripts and prior
  configuration is under `D:\SSDProjects\Omni-LegacyCSharpHub-archive-20261018`.
  All surviving C#/script source files were hash-verified in the archive before
  deletion from the checkout. Locked generated C# binary/cache files remain
  temporarily in the ignored `OmniSync.Hub/` folder, which contains no C#
  project file; avoid force-closing applications to delete these caches.
- A temporary ignored auth-config compatibility location may remain at the
  retired source path while the **existing Rust process** retains that path
  from its launch environment. After a normal Rust restart using the updated
  launcher, this compatibility file may be retired.
- Independent OmniMCP-Lite watchdog script moved to
  `scripts/services/omnimcp_lite_watchdog.py` and its Windows task action was
  updated, without touching either MCP server's process.

## Downstream actions
- Android build/deploy and integration scripts now locate Rust credentials
  under LocalAppData (or `OMNIHUB_AUTH_CONFIG`).
- CLI SignalR auth uses a configured secret instead of a checked-in default.
- OmniZones detects Rust Hub ownership when its single-instance mutex is busy.
- Old C#-specific test harnesses, wrappers and Ugoira hosted-service wrappers
  were retired; the worker/standalone implementations remain.
- Chrome/Firefox SignalR endpoints remain valid because Rust retains port 5000
  and `/signalrhub`; Web Gateway stays at 3333.
- Rust startup references to the private auth config were adjusted in the
  separate Omnish working tree. **Do not restart production to validate a
  source-only startup change.**

Retired historical implementation is recoverable from the first checkpoint
commit in this repository and the local archive. No Rust source was deleted.
