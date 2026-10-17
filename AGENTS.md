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
Read [docs/SERVICE_RECOVERY.md](docs/SERVICE_RECOVERY.md) first.
The Rust Hub is scheduled as `OmniHub Rust` (5000/3333/5055). Its independent
Rust MCP sidecar is `OmniHub Rust MCP` (8770). OmniMCP-Lite (8771)
remains independent, with supervisor source at `scripts/services/omnimcp_lite_watchdog.py`.
Never reactivate the disabled C# Hub or C# watchdog.

## Local web Gateway
The Rust Gateway listens at `http://localhost:3333/gateway/`.
See [docs/GATEWAY.md](docs/GATEWAY.md). Register tools through the
standalone MCP capability, not via the removed C# implementation.
The legacy `OmniSync.Web/www/gateway/` UI mirrors Rust-owned web assets;
coordinate UI changes with `D:\SSDProjects\Tools\Omnish\main`.
