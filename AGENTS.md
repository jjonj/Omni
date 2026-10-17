# Omni repository agent instructions

## User-maintained Obsidian notes

The user's Omni notes are available at `OmniNotes\`.

For feature design, planning, or implementation work:
- read `OmniNotes\_Agent Index.md`;
- search the relevant files under `OmniNotes\Inbox`, `OmniNotes\Design`, and `OmniNotes\Backlog`;
- treat Inbox notes as rough context, not as tasks to implement unless the user explicitly asks;
- preserve user-written notes rather than deleting them when work is completed.

`OmniNotes` is a local directory junction to the user's Google Drive / Obsidian vault and is intentionally gitignored.

## Modifying setups

Setups are the OmniSync Hub's launch presets. The user may also call them "activities"; treat the two names as the same thing.

- Stored in `%APPDATA%\OmniSync\settings.json` under `Setups`. Each setup has `Name`, a `Shortcut` (`1`-`9`, `Q`-`M`, `F1`-`F24`, or `SHIFT+` variants), and an `Actions` list.
- Each action has a `Type` (0 = OpenFolder, 1 = RunProgram, 2 = Terminal, 3 = RunOnce, 4 = OmniAction, 5 = Macro), a `Path`, and `Arguments`. Set `ReuseWindow` and `PositionWindow` to `false` for programs that must always receive their arguments, such as `fomni.exe`.
- The Hub loads this file at startup and rewrites it on save. Back up the file, edit it, and tell the user to restart the Hub, or the change may be lost.
- Prefer editing through the Hub's Setups UI when it is available; file edits are a fallback.

## Service recovery and connectivity alerts

- Before diagnosing or restarting OmniHub, OmniMCP-Lite, Tailscale, watchdogs, or sleep/resume connectivity, read [docs/SERVICE_RECOVERY.md](docs/SERVICE_RECOVERY.md).
- Verify local MCP and public Funnel independently. A running service or enabled Funnel does not prove public access works; repeated reads of one cached probe are not independent failures.
- Preserve scheduled-task launch details, Funnel routes, and diagnostic evidence. Use targeted recovery, verify actual calls afterward, and keep monitoring enabled.
- For connectivity alert changes, follow the guide's confirmation and dismissal policy and identify whether C# or Rust owns the live popup before deploying.

## Local web tools / Gateway

- The shared web-tool launcher and workspace is `http://localhost:3333/gateway/`. For implementation and registry details, read `OmniSync.Hub/docs/GATEWAY.md`.
- When creating a new local web tool, register it through standalone **OmniMCP-Lite** `register_web_tool` (do not modify the Hub registry through HTTP or rely on the retired MCP forwarding port). Use a stable lowercase slug, descriptive title, absolute working directory, executable/argument array, and one or more loopback HTTP health checks. A frontend + backend stack needs both checks.
- Users can launch registered tools via `http://localhost:3333/<slug>`; the Hub starts an offline tool on demand and then redirects.
- Keep registration idempotent and choose a `contains` marker that uniquely identifies the frontend when practical. Avoid wrapping launch commands in a shell unless necessary; prefer a direct executable and arguments.
- The Gateway is an extensible workspace, not a replacement for the existing Hub dashboard or app servers. UI files: `OmniSync.Web/www/gateway/`; backend: `OmniSync.Hub/src/OmniSync.Hub/Logic/Services/Gateway/`.

## OmniSweep in Gateway

- Desktop OmniSweep and web OmniSweep at http://localhost:3333/gateway/sweep.html use the SAME live HubSettingsService singleton and %APPDATA%\OmniSync\settings.json. Do not create a second web config, duplicate setups table, or localStorage copy of setup definitions.
- Use GatewaySweepService / GatewaySweepRoutes for browser read/write/launch operations. Browser changes must call existing HubSettingsService mutation methods so desktop SettingsChanged observers refresh; the browser polls automatically for desktop changes. Any new edit endpoint must use revision conflict checks and the local-only, same-origin protection already present.
- Preserve ProjectAction IDs and captured Layout metadata when editing setup actions. Settings and macros owned by the Hub must stay authoritative.
