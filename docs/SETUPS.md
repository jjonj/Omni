# OmniSweep setups

Setups replace the old project launcher. Old saved projects are discarded.

Open OmniSweep with the hotkey listed as **OmniSweep** in Hub settings (default
Ctrl+Alt+P). The grid has stable shortcuts: digits, letters except WASD, then
function keys and Shift combinations for larger collections. Drag tiles to
reorder without changing their shortcuts.

- WASD or arrows select tiles; Space or Enter opens the selected setup.
- `/` or Ctrl+F opens search for setups, macros and files. Typing there never
  activates setup shortcuts. Escape returns to the grid, then closes Sweep.
- Tab and Shift+Tab cycle through the controls and the selected tile.
- Right-click a tile to rename, change its shortcut, re-snapshot, replace its
  captured steps, or open its editor in Hub settings / Setups.

## Snapshot

Click **Snapshot**, then arrange windows while the click-through screen outlines
are visible. Normal desktop input and OmniZones snapping continue to work.

- Ctrl+Alt+Enter captures normal, non-minimized windows and creates a tile.
- Ctrl+Alt+Backspace skips capture, creates an empty setup and opens its editor.
- Ctrl+Alt+Escape cancels.

Hub/overlay windows and Windows shell surfaces are excluded. Terminal captures
start as fresh shells; configure their working directory and command in settings.
Re-snapshot updates captured windows and their geometry while preserving matched
steps' commands, arguments and directories, plus manually added steps. **Replace
snapshot** starts the captured step list over.

## Steps

Steps run in the order shown in the editor; Up/Down changes that order.

| Kind | Behavior |
| --- | --- |
| Program | Opens a program with arguments; normally reuses an existing window. |
| Folder | Opens or reuses the specified Explorer folder window. |
| Terminal | Opens a new cmd, PowerShell, pwsh or Windows Terminal window and runs its command; the shell stays open. |
| RunOnce | Executes a shell command, program or script and waits for completion. |
| OmniAction | Dispatches an Omni command with an optional JSON payload. |
| Macro | Runs a named/ID macro, or the inline script in the Command field. |

For Terminal and shell-based RunOnce steps, put the shell in Program and the
command in Command. For a one-time executable/script, put its path in Program
and its arguments in Arguments.

Scripts receive the clipboard at activation through `OMNI_CLIPBOARD`: use
`$env:OMNI_CLIPBOARD` in PowerShell or `%OMNI_CLIPBOARD%` in cmd. An OmniAction
JSON string value of `"{clipboard}"` also expands to clipboard text. For example,
`LAUNCH_AI_SESSION` with `{"Workspace":"D:\\Wartribes","Job":"{clipboard}"}`
can replace the former Wartribes Job button.

The editor supports an optional global hotkey, window title matching, exact pixel
sizes/positions, proportional geometry, target monitors and OmniZones placement.
Ordinary windows are reused once per setup step; terminals default to fresh
windows. A saved zone is restored when that OmniZones layout is active; otherwise
the saved rectangle is restored. Missing displays map to surviving displays,
with affected windows placed behind the other windows.
