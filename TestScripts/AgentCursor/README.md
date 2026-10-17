# Independent OmniHub cursor POC

The Hub draws a blue `OMNI AI` pointer independently of Windows' physical mouse.
Moving it never calls `SendInput`, `SetCursorPos`, `SetForegroundWindow` or `SetFocus`.
Clicking uses UI Automation or direct window messages. The overlay is topmost,
does not activate, passes physical clicks through, and auto-hides after five seconds idle.
Idle hiding only affects visibility: its position stays ready, and the next move
or click shows it again and restarts the timeout. Status queries do not keep it visible.
Explicit `agent_cursor_hide` disarms clicks until another move, as before.

## Try it

After building and starting the Hub with `python build_run_omnihub.py`:

```powershell
python TestScripts/AgentCursor/test_agent_cursor.py --demo
```

This opens native and WPF test windows, clicks the native button with the blue
pointer, and keeps the demo visible. Close the native test window to dismiss
the demo. Omit `--demo` for the automated integration tests that clean up.
The test checks actual native and WPF actions and physical mouse position after
the recipient processes its clicks. It asserts foreground preservation for
native message clicks and reports WPF UI Automation's foreground effect.
Moving your mouse during those short checks can
cause an assertion failure.

## Agent commands

Connect an MCP client to `http://127.0.0.1:8770/mcp` (or your configured MCP port).
Refresh the client's tool list after rebuilding; installed connector schemas may
need updating before these new tools appear there.

| Tool | Arguments | Purpose |
| --- | --- | --- |
| `agent_cursor_status` | `{}` | Cursor visibility, readiness and position, physical mouse, foreground HWND, target HWND and monitor bounds. |
| `agent_cursor_move` | `{"x":238,"y":223}` | Show/move the cursor in absolute **physical desktop pixels**. |
| `agent_cursor_click` | `{"button":"left","count":1,"mode":"auto"}` | Act on the visible client area at the cursor. |
| `agent_cursor_hide` | `{}` | Hide the pointer; a new move is required before another click. |

`auto` first tries an accessible Invoke, Toggle or SelectionItem action for a
single left click, and uses window messages if there is no supported action.
`uia` requires a supported accessibility action. `messages` forces direct Win32
messages and supports left/right clicks and double clicks (`count:2`).

Coordinates include negative monitor origins. Use the status monitor bounds to
map screenshots correctly: a half-size screenshot requires multiplying local
coordinates by two and adding that monitor's desktop origin. Positions between
monitors or outside every connected monitor are rejected.

The same operations are available over the existing authenticated Hub API:

- `GET /api/external/agent-cursor?key=...`
- `POST /api/external/agent-cursor/move?key=...` with `{"x":238,"y":223}`
- `POST /api/external/agent-cursor/click?key=...` with `{"mode":"auto","button":"left","count":1}`
- `POST /api/external/agent-cursor/hide?key=...`

## What this proves and its limits

This is an independent visual pointer with background control actions, not an
additional hardware mouse input stream. Windows and individual apps still have
shared focus, capture and keyboard state. A target app can change focus itself
(the WPF test's UI Automation action brought its window to the foreground);
the Hub does not request activation or focus. Keyboard typing, dragging, title
bars, secure desktops and hardware-only/game input are outside this POC.

UI Automation broadens support to accessible controls such as WPF buttons.
Direct messages work only in apps that accept them. A `posted` response means
messages entered the target's queue, and `invoked` means an accessibility call
returned; verify the result with a screenshot or app state. There is no fallback
that temporarily moves your physical mouse.

UI Automation runs on a separate MTA thread with a three-second wait limit.
If lookup times out, its pending worker is prevented from starting an action
later. If an action already started and its provider does not return, the result
is uncertain and must be inspected before retrying. No message fallback is sent
on timeout or provider errors, and further clicks are rejected while that worker
is still pending. Movement and hiding remain available.

Microsoft references: [mouse messages](https://learn.microsoft.com/en-us/windows/win32/inputdev/about-mouse-input),
[UI Automation patterns](https://learn.microsoft.com/en-us/windows/win32/winauto/uiauto-controlpatternsoverview),
[UI Automation threading](https://learn.microsoft.com/en-us/dotnet/framework/ui-automation/ui-automation-threading-issues).
