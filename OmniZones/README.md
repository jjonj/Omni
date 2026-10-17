# OmniZones

Drag a window by its title bar, drop it in a zone. Lives in the system tray.

## Layout

- `src/OmniZones.Core` - everything (zone math, drag watcher, hotkeys, editor, settings window). No Omni dependencies.
- `src/OmniZones.App` - standalone tray app (`OmniZones.exe`).
- Rust OmniHub in `D:/SSDProjects/Tools/Omnish/main` hosts its own OmniZones runtime, sharing the same single-instance lock with the standalone app. Only one copy runs at a time.
- `tests/OmniZones.Core.Tests` - unit tests. `TestScripts/WindowSnap/test_omnizones_e2e.py` - real mouse-drag tests (takes over the mouse ~1 min).

## Build / share

`powershell -File publish.ps1` -> `dist/OmniZones.zip` (single self-contained exe, no .NET install needed).

## Using it

Tray icon -> **Settings** (double-click) or **Edit zones**. Settings live in `%APPDATA%\OmniZones\settings.json`.

- **Simple mode**: plain zones only. No scenes, stacks, peek docks, app preferences or layout hotkeys.
- **Require Shift to snap**: off = zones show while dragging and Shift drags freely; on = hold Shift to see zones and snap.
- **Occupied zone**: Swap or Stack by default; hold Ctrl (or Alt, or nothing) while dropping to do the opposite.
- Hotkeys: Win+arrows (zone left/right, fill, unsnap), Win+Ctrl+Z undo. Not in simple mode: Win+Alt+layout key, Win+Alt+Up/Down cycle stack, Win+Alt+T gather, Win+` peek.
