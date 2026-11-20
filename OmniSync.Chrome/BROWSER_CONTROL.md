# OmniSync Chrome extension â†” Rust OmniMCP browser_control

Updated 2026-10-29. **Canonical installed extension:** `D:\SSDProjects\Omni\OmniSync.Chrome` (manifest v1.3).
**Canonical running MCP server:** Rust sidecar in `D:\SSDProjects\Tools\Omnish\main`, via scheduled task **OmniHub Rust MCP** (current loopback port 8772, bridge 18773), not the retired C# candidate `McpHelloProbe`.

## How to use

The extension starts on its own, without pairing tokens or per-origin permission prompts.
The extension now requests **all HTTP and HTTPS host permissions**, without individual LAN/IP rules. Its own DOM restrictions on password fields, normal form submission, and links have been removed; Chromium still enforces native protected-page and permission boundaries. Inspection continues to omit input values and redact query strings/fragments. The popup has **Enable/Pause**. A manifest permission expansion may still require Vivaldi's approval on first activation.

ChatGPT/other MCP clients use **one tool named `browser_control`** with a required `action` string:

```text
browser_control(action="tabs")
browser_control(action="navigate", url="https://lolalytics.com/lol/anivia/build/", newTab=true)
browser_control(action="inspect", tabId=123)
browser_control(action="click", tabId=123, selector="button#play")
browser_control(action="bring_to_front", tabId=123)
browser_control(action="type", tabId=123, selector="input[type=search]", text="Anivia")
```

### Embedded chat widgets

`inspect`/`find` now include accessible child frames and **open** shadow roots. For a
child frame, the reported selector begins `@frame(<id>,<encoded-origin>[,<document-id>]) `; for
shadow controls it contains ` >>> ` between the host and its shadow content.
Pass the selector back **unchanged** to `click`, `type`, `focus`, `select`, or
`wait_for`. The frame's original origin is checked before acting; where available the
exact document ID is also pinned (including opaque `null`-origin sandbox frames), avoiding an
unintended click if the frame navigates. Closed shadow roots and browser-protected
frames cannot be read by extension scripts.

`click` currently dispatches `HTMLElement.click()` (not a trusted hardware
activation), so `clicked:true` confirms dispatch only, **not** that an embedded
messenger opened, a popup was allowed, or any backend operation succeeded.
The response explicitly includes `trustedUserActivation:false`.
All 15 actions: tabs, navigate, inspect, click, type, activate, bring_to_front, close,
reload, page_state, scroll, focus, find, wait_for and select. `activate` selects a tab in its existing browser window without bringing the window forward. `bring_to_front` selects the tab, restores its containing window if minimized, and asks Chrome to foreground that exact window. The response includes `focused` (Chrome's reported focus state), `windowId`, and `restored`; a `focused: false` reply is not confirmation of OS foreground focus.

For browsing, agents must **use browser_control**; launching Vivaldi via shell or using mouse input does not test the extension. A failed browser click is **never** automatically retried or converted to desktop input. `FRAME_RESULT_UNAVAILABLE`/`FRAME_NAVIGATED` mean the click outcome may be unknown; inspect current tabs/page state first. `FRAME_ACCESS_RESTRICTED`/`SENSITIVE_ACTION_RESTRICTED` require respecting browser/extension boundaries. For a separately authorized, non-sensitive physical interaction only, native OMCP `list_windows`, `agent_cursor_move(target_hwnd=...)`, and explicit `agent_cursor_click(mode="physical")` may work when the target is visible and unobscured; **never** use that route for a platform-blocked call, OAuth permissions, credentials or other protected interaction.

## Connection

The Rust MCP endpoint currently runs at `http://127.0.0.1:8772/mcp` through the configured public proxy. The browser extension polls **`http://127.0.0.1:18773/browser/poll`** and posts responses to `/browser/result`. The bridge stays on loopback and does not require a separate pairing token. The plugin's existing remote-access configuration is separate from browser pairing.

If `browser_control` is absent from the ChatGPT tool menu after upgrading the Rust MCP sidecar, reconnect/refresh plugin discovery; existing sessions may cache the tool list. Avoid starting new browser processes to work around a missing tool.

**Authoritative design, tests, deployment and rollback:** `D:\SSDProjects\Tools\Omnish\main\docs\BROWSER_CONTROL_MCP_2026-10-18.md`.

Real Chromium end-to-end validation of the unified 14-action tool passed against a **disposable** Chrome profile and isolated Rust MCP listener before the 2026-10-18 Rust sidecar cutover. The user's installed profile was not touched by the test.

2026-10-22: original 8772/18773 restored after inherited socket-handle reclamation. Actual OMCP browser_control tabs succeeded; no further extension reload was needed for the running bridge.

2026-10-29: After manual extension reload, live `browser_control(action="tabs")` DID list the existing `http://192.168.1.1/` tab (`1542702680`), and `page_state` succeeded. The previous omission came from the extension's HTTP host filter, not a missing browser window. A subsequent source update removes HTTP origin allowlists entirely. The **separate** OMCPT server must be rebuilt/redeployed to accept arbitrary browser navigation URLs; extension reload alone does not change server-side validation.

### Browser action outcomes and platform protections

`DOM_TARGET_NOT_FOUND`, `DOM_INVALID_SELECTOR`, `DOM_ORIGIN_CHANGED`, and `DOM_PRECONDITION_FAILED` identify known failures before a DOM mutation began. `DOM_ACTION_FAILED` means an injected action threw after it may have started; its outcome is unknown and it must not be blindly retried. `FRAME_RESULT_UNAVAILABLE` remains unknown when Chromium returned no script result (e.g. during navigation). Returned DOM errors contain no raw page exception text or form values. `type` returns `valueMatches` for the immediate field value and `saved: "not_verified"`; `click` returns `confirmation: "CLICK_DISPATCHED_ONLY"`. Neither result certifies that router settings were persisted.

A tool invocation rejected before execution with "This tool call was blocked by OpenAI's safety checks" is a separate platform-side decision, not a Chromium/extension error. Do not use another interface to replay the blocked credential entry. The user can complete protected inputs manually in Vivaldi.

Live verification on 2026-10-29: the unpacked Vivaldi extension automatically reloaded to build `browser-control-dom-outcomes-20261029` (`autoReloadCount: 4`), while the original router tab `1542702680` remained discoverable. A **read-only** `wait_for` with a deliberately malformed selector returned `DOM_INVALID_SELECTOR` with `retrySafe: true`, proving the new error classification reached the live extension. No router settings were changed.

### Unpacked extension automatic refresh

The Vivaldi extension folder is **`D:\SSDProjects\Omni\OmniSync.Chrome`**, not `OmniSync.Android` (the Android app source). Install the unpacked **Chrome** folder once under `vivaldi:extensions` in developer mode. Its five-minute alarm calls `chrome.runtime.reload()` only for a development/unpacked install and only when no browser-control command has arrived for two minutes. Reload increments a persisted counter shown as `extension.autoReloadCount` in `browser_control(action="tabs")`; `extension.build` confirms which source version the running worker loaded. No extension-page navigation, browser-tab reload, or T3 restart is needed. Other install types are untouched; browser-required new host permission approvals still need a human.
