# OmniMCP browser control — simplified local setup

Updated 2026-10-17. Canonical Chrome/Vivaldi extension:
`D:\SSDProjects\Omni\OmniSync.Chrome` (manifest v1.2).
Standalone OmniMCP server:
`D:\SSDProjects\Tools\McpHelloProbe` (candidate v0.5.11).

## Quick usage

The extension starts local browser control automatically when loaded. There is **no pairing-code field, no per-website approval list, and no origin-by-origin grant prompt**. The popup only provides **Enable** and **Pause**. Reload the unpacked extension after updating its files. The new manifest requests HTTPS host access once as part of installation/update; Chrome may ask for that extension permission.

The extension can discover, navigate and interact with regular HTTPS tabs, plus local HTTP sites at `localhost` or `127.0.0.1`. Chrome internal pages, local files and other untrusted schemes remain unavailable. The extension does not inspect credential/payment/OTP form values and restricts sensitive form interactions. It returns bounded visible page text and never includes URL query strings/fragments in tab lists.

Supported MCP tools: `browser_tabs`, `browser_navigate`, `browser_inspect`, `browser_click`, `browser_type`, `browser_activate`, `browser_close`, `browser_reload`, `browser_page_state`, `browser_scroll`, `browser_focus`, `browser_find`, `browser_wait_for`, `browser_select`.

## Network boundary: what changed and what did NOT

The **browser extension ↔ localhost bridge** at `127.0.0.1:18772/browser/poll` and `/browser/result` no longer requires a separate shared pairing token. Its handlers only accept loopback traffic at the dedicated bridge port, reject requests carrying ordinary website origins and reject `no-cors` / navigation requests. Chrome's extension-origin fetch works automatically. Browser actions continue to use short-lived queued commands, no direct injected arbitrary JS.

The **MCP client ↔ server** connection is separate. Because OmniMCP also exposes full shell/filesystem control, browser control remains off unless `OMNI_BROWSER_CONTROL_ENABLED=1` and a server-side `OMNI_BROWSER_CONTROL_TOKEN` with at least 32 characters is configured. **All** `/mcp` requests then require `Authorization: Bearer <token>` to avoid exposing the computer to unauthenticated public MCP traffic. The token is configured for MCP clients only; **do not enter it in Chrome**. This is required even when the extension itself no longer asks for a code.

No public routes, OmniHub, Vivaldi user profile, running scheduled task or production OmniMCP binary were changed by this source update. The live server still needs a separately coordinated upgrade; the v0.5.8 service will not advertise browser tools. Avoid repointing the public MCP route to a tokenless/unauthenticated service.

## Local development / smoke checks

Build a new, versioned standalone candidate (not the production binary):

```powershell
cd D:\SSDProjects\Tools\McpHelloProbe
dotnet publish McpHelloProbe.csproj -c Release -o published-v0.5.11-browser-easy
python TestFiles/test_browser_control.py published-v0.5.11-browser-easy/OmniMCP.exe
python TestFiles/test_robustness.py published-v0.5.11-browser-easy/OmniMCP.exe
python TestFiles/test_browser_live_chrome.py

cd D:\SSDProjects\Omni\OmniSync.Chrome
node --check browser-control.js
node --check browser-popup.js
node test-browser-control.cjs
```

The live-browser test uses a **separate disposable Chrome profile**, a synthetic localhost page, a randomly generated MCP-only test token, a temporary shadow MCP listener and the current canonical extension directory. It never reads the real Vivaldi profile. Tests verified all 14 tool paths, input redaction and default local autoconnect without typing a token or approving individual sites.

### Remaining caveat

Giving the extension broad HTTPS host permissions means its browser tools can inspect **visible non-form content** on any HTTPS website once the authenticated MCP caller requests it. The convenience change does not make that content non-sensitive. The popup **Pause** button disables browser operations if desired.

If the Chrome extension is reloaded but tools still do not appear, check the standalone OmniMCP service version and server-side MCP authorization configuration before modifying the extension again.
