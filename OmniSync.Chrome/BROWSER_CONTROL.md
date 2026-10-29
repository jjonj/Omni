# OmniSync Chrome extension â†” Rust OmniMCP browser_control

Updated 2026-10-20. **Canonical installed extension:** `D:\SSDProjects\Omni\OmniSync.Chrome` (manifest v1.3).
**Canonical running MCP server:** Rust sidecar in `D:\SSDProjects\Tools\Omnish\main`, via scheduled task **OmniHub Rust MCP** (current loopback port 8772, bridge 18773), not the retired C# candidate `McpHelloProbe`.

## How to use

The extension starts on its own, without pairing tokens or per-origin permission prompts.
The extension requests HTTPS host permission at install/reload; localhost HTTP and the exact router host `http://192.168.1.1/*` are also permitted. No wildcard HTTP access is granted. Reload the unpacked extension in Vivaldi's Extensions page to activate manifest/source changes (and accept any new host-permission prompt); do not restart Vivaldi or T3. The popup has only **Enable/Pause**. Router login/password, OAuth, form-submission and sensitive-field protections remain in place.

ChatGPT/other MCP clients use **one tool named `browser_control`** with a required `action` string:

```text
browser_control(action="tabs")
browser_control(action="navigate", url="https://lolalytics.com/lol/anivia/build/", newTab=true)
browser_control(action="inspect", tabId=123)
browser_control(action="click", tabId=123, selector="button#play")
browser_control(action="bring_to_front", tabId=123)
browser_control(action="type", tabId=123, selector="input[type=search]", text="Anivia")
```

All 15 actions: tabs, navigate, inspect, click, type, activate, bring_to_front, close,
reload, page_state, scroll, focus, find, wait_for and select. `activate` selects a tab in its existing browser window without bringing the window forward. `bring_to_front` selects the tab, restores its containing window if minimized, and asks Chrome to foreground that exact window. The response includes `focused` (Chrome's reported focus state), `windowId`, and `restored`; a `focused: false` reply is not confirmation of OS foreground focus.

For browsing, agents must **use browser_control**; launching Vivaldi via shell or using mouse input does not test the extension. A failed browser click is **never** automatically retried or converted to desktop input. `FRAME_RESULT_UNAVAILABLE`/`FRAME_NAVIGATED` mean the click outcome may be unknown; inspect current tabs/page state first. `FRAME_ACCESS_RESTRICTED`/`SENSITIVE_ACTION_RESTRICTED` require respecting browser/extension boundaries. For a separately authorized, non-sensitive physical interaction only, native OMCP `list_windows`, `agent_cursor_move(target_hwnd=...)`, and explicit `agent_cursor_click(mode="physical")` may work when the target is visible and unobscured; **never** use that route for a platform-blocked call, OAuth permissions, credentials or other protected interaction.

## Connection

The Rust MCP endpoint currently runs at `http://127.0.0.1:8772/mcp` through the configured public proxy. The browser extension polls **`http://127.0.0.1:18773/browser/poll`** and posts responses to `/browser/result`. The bridge stays on loopback and does not require a separate pairing token. The plugin's existing remote-access configuration is separate from browser pairing.

If `browser_control` is absent from the ChatGPT tool menu after upgrading the Rust MCP sidecar, reconnect/refresh plugin discovery; existing sessions may cache the tool list. Avoid starting new browser processes to work around a missing tool.

**Authoritative design, tests, deployment and rollback:** `D:\SSDProjects\Tools\Omnish\main\docs\BROWSER_CONTROL_MCP_2026-10-18.md`.

Real Chromium end-to-end validation of the unified 14-action tool passed against a **disposable** Chrome profile and isolated Rust MCP listener before the 2026-10-18 Rust sidecar cutover. The user's installed profile was not touched by the test.

2026-10-22: original 8772/18773 restored after inherited socket-handle reclamation. Actual OMCP browser_control tabs succeeded; no further extension reload was needed for the running bridge.

2026-10-29: source change permits only `http://192.168.1.1/` additionally. Source patch alone is not live: reload the unpacked extension and deploy/restart **only** the independent OMCPT sidecar using its documented owner-coordinated procedure, then confirm `browser_control(action="tabs")` includes the router tab. Do not equate a source commit with live Vivaldi permission or a logged-in router session.

### Unpacked extension automatic refresh

The Vivaldi extension folder is **`D:\\SSDProjects\\Omni\\OmniSync.Chrome`**, not `OmniSync.Android` (the Android app source). First install the unpacked **Chrome** folder once under `vivaldi:extensions` in developer mode, or reload that existing extension once to activate this updated service worker. Later, its five-minute alarm calls `chrome.runtime.reload()` only for a development/unpacked install and only when no browser-control command has arrived for two minutes. This refreshes sources already on disk without navigating extension settings or restarting browser tabs; other install types are untouched. Reloading is postponed during recent activity, and any browser-required new host permission approval still needs manual confirmation. An automatic refresh can briefly interrupt the extension's own bridge connections.
