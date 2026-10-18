# OmniSync Chrome extension ↔ Rust OmniMCP browser_control

Updated 2026-10-18. **Canonical installed extension:** `D:\SSDProjects\Omni\OmniSync.Chrome` (manifest v1.2).
**Canonical running MCP server:** Rust sidecar in `D:\SSDProjects\Tools\Omnish\main`, via scheduled task **OmniHub Rust MCP** (port 8770), not the retired standalone C# candidate `McpHelloProbe`.

## How to use

The extension starts on its own, without pairing tokens or per-origin permission prompts.
The extension requests HTTPS host permission at install/reload; local localhost HTTP is also available. The popup has only **Enable/Pause**. The site and authentication-code/password protections remain in place.

ChatGPT/other MCP clients use **one tool named `browser_control`** with a required `action` string:

```text
browser_control(action="tabs")
browser_control(action="navigate", url="https://lolalytics.com/lol/anivia/build/", newTab=true)
browser_control(action="inspect", tabId=123)
browser_control(action="click", tabId=123, selector="button#play")
browser_control(action="type", tabId=123, selector="input[type=search]", text="Anivia")
```

All 14 actions: tabs, navigate, inspect, click, type, activate, close,
reload, page_state, scroll, focus, find, wait_for and select.

For browsing, agents must **use browser_control**; launching Vivaldi via a shell command or using mouse input does not test the plugin.

## Connection

The Rust MCP endpoint remains `http://127.0.0.1:8770/mcp` through the configured public proxy. The browser extension polls **`http://127.0.0.1:18772/browser/poll`** and posts responses to `/browser/result`. The bridge stays on loopback and does not require a separate pairing token. The plugin's existing remote-access configuration is separate from browser pairing.

If `browser_control` is absent from the ChatGPT tool menu after upgrading the Rust MCP sidecar, reconnect/refresh plugin discovery; existing sessions may cache the tool list. Avoid starting new browser processes to work around a missing tool.

**Authoritative design, tests, deployment and rollback:** `D:\SSDProjects\Tools\Omnish\main\docs\BROWSER_CONTROL_MCP_2026-10-18.md`.

Real Chromium end-to-end validation of the unified 14-action tool passed against a **disposable** Chrome profile and isolated Rust MCP listener before the 2026-10-18 Rust sidecar cutover. The user's installed profile was not touched by the test.
