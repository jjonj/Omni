# Omni repository agent instructions

## Scope and architecture
This repository contains Android, Chrome, Firefox, CLI, Web, OmniZones and utility clients.
The **production Hub is Rust** at `D:\SSDProjects\Tools\Omnish\main` (separate Git repository).
The obsolete C# `OmniSync.Hub` source was retired on 2026-10-18.
Do not recreate C# Hub project references or restart the live Rust service as part of client builds.

## User-maintained Obsidian notes
`OmniNotes/` is an ignored junction to the user's vault. Before feature work read
`design.txt`, `OmniNotes/_Agent Index.md` and relevant Inbox/Design/Backlog notes.
Treat Inbox as rough ideas, not implementation authorization; preserve user notes.

## Runtime state and settings
- User settings, setups, macros, gateway registrations: `%APPDATA%\OmniSync\`.
- Current Hub authentication configuration: `%LOCALAPPDATA%\OmniHub\config\appsettings.json` (private, NEVER commit its values).
- `OMNIHUB_AUTH_CONFIG` overrides the path for tools that use the Hub auth config.
- Setups are edited in the Rust Hub UI. File edits require backup and a coordinated reload to avoid lost changes.
- Rust Hub source and startup scripts live only in the separate Omnish repo. Changes there must be coordinated with any ongoing Rust development.

## Recovery and MCP
Read the [current Omni Registry service map](<B:/GDrive/ProjectsG/Omni Registry/service-map-current-2026-10-24.md>) for **expected configuration versus observed live PID/image/health**, then [docs/SERVICE_RECOVERY.md](docs/SERVICE_RECOVERY.md) and the Rust owner's `D:\SSDProjects\Tools\Omnish\main\docs\OMNIHUB_RECOVERY.md` for recovery details. Historical port/bridge and OmniMCP-Lite guidance in older recovery sections is **superseded** by the Registry map and current launchers.
The production Rust Hub is task `OmniHub Rust` (5000/3333/5055). Independent MCP **Core** is task `OmniHub Rust MCP` at loopback **8772** (`/mcp`), and independent MCP **Tools** is task `OmniHub Rust MCP Tools` at loopback **8773** (`/mcp`), owning browser bridge **18773**. The public OAuth gateway is separate at **8793**. Former Rust MCP **8770** and retired standalone OmniMCP-Lite **8771** are **not** valid current recovery targets. Verify actual process/listener ownership before any restart; do not reactivate the disabled C# Hub or watchdog.

## Local web Gateway
The Rust Gateway listens at `http://localhost:3333/gateway/`.
See [docs/GATEWAY.md](docs/GATEWAY.md). Register tools through the
standalone MCP capability, not via the removed C# implementation.
The legacy `OmniSync.Web/www/gateway/` UI mirrors Rust-owned web assets;
coordinate UI changes with `D:\SSDProjects\Tools\Omnish\main`.
