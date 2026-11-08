# OmniZones

Drag a window by its title bar, drop it in a zone. Lives in the system tray.

## Layout

- `src/OmniZones.Core` - everything (zone math, drag watcher, hotkeys, editor, settings window). No Omni dependencies.
- `src/OmniZones.App` - standalone tray app (`OmniZones.exe`).
- Rust OmniHub in `D:/SSDProjects/Tools/Omnish/main` hosts its own OmniZones runtime, sharing the same single-instance lock with the standalone app. Only one snapping runtime runs at a time.
- Both frontends edit the same `%APPDATA%\\OmniZones\\settings.json` file. On its first upgraded launch OmniHub can import a newer, old Hub profile (`%APPDATA%\\OmniHub\\zones.json`), backing up the previous standalone settings before replacement. Test-only path overrides remain isolated.
- `tests/OmniZones.Core.Tests` - unit tests. `TestScripts/WindowSnap/test_omnizones_e2e.py` - real mouse-drag tests (takes over the mouse ~1 min).

## Build / share

`powershell -File publish.ps1` -> `dist/OmniZones.zip` (single self-contained exe, no .NET install needed).

## Using it

Tray icon -> **Settings** (double-click) or **Edit zones**. Settings live in `%APPDATA%\OmniZones\settings.json`.

- **Simple mode**: plain zones only. No scenes, stacks, peek docks, app preferences or layout hotkeys.
- **Require Shift to snap**: off = zones show while dragging and Shift drags freely; on = hold Shift to see zones and snap.
- **Occupied zone**: Swap or Stack by default; hold Ctrl (or Alt, or nothing) while dropping to do the opposite.
- **DisplayFusion / multiple monitor arrangements**: each named layout has a main-monitor default plus optional *physical-monitor* overrides. The Hub editor can select a connected or Windows-known inactive display, and the standalone editor can edit connected ones. Overrides are keyed by hardware identity (`id:MONITOR\\...`) and follow that display when its DISPLAY number or primary role changes. Legacy DISPLAYn overrides still work for secondary displays. Saved layouts can be switched manually/hotkey; automatic switching between named layouts based on display topology is not implemented.
- Hotkeys: Win+arrows (zone left/right, fill, unsnap), Win+Ctrl+Z undo. Not in simple mode: Win+Alt+layout key, Win+Alt+Up/Down cycle stack, Win+Alt+T gather, Win+` peek.
