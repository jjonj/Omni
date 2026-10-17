# OmniMCP browser control (development candidate)

Status (2026-10-17): **merged into the canonical Omni Chrome extension source.** The extension has not been reloaded or published as part of this merge, and the production OmniMCP service has not been updated.

## Repositories and ownership

- Chrome Manifest V3 extension: `D:\SSDProjects\Omni\OmniSync.Chrome` (existing `Omni` Git repository). Development initially took place in an isolated worktree on `feature/browser-control-20261017`; the completed changes are now merged into the canonical `OmniSync.Chrome` directory on `master`. Existing SignalR/Android/Hub browser commands remain separate.
- Supported, standalone OmniMCP source: `D:\SSDProjects\Tools\McpHelloProbe` (not a Git repository). New source `BrowserControlTool.cs`, minor `Program.cs` changes; originals backed up at `backups/browser-control-20261017`. Production scheduled task still points to the older published executable and was NOT modified.
- Rust OmniHub successor in `D:\SSDProjects\Tools\Omnish\main` is not the owner of the supported MCP endpoint. Its legacy embedded MCP listener at 8770 is retired. No bridge or proxy to it is needed.

## Security model

Browser control is **disabled by default**. OmniMCP exposes its fourteen browser tools only when both `OMNI_BROWSER_CONTROL_ENABLED=1` and a strong `OMNI_BROWSER_CONTROL_TOKEN` (32+ characters) are configured in the **process environment**. When enabled, **every request to `/mcp`** (including existing shell and filesystem tools and initialization) must have `Authorization: Bearer <token>`; unauthenticated public clients fail closed with HTTP 401. This global guard is essential because the existing publicly reachable MCP service otherwise exposes privileged shell tools that could circumvent a browser-specific token.

The extension independently requires a matching pairing token and an explicit enabled toggle. Its popup obtains site host permissions through a direct Chrome user gesture. The worker also checks a separately stored per-origin allowlist and the Chrome host-permission grant for **each action**; unapproved tabs do not appear in discovery, and navigation is restricted to approved HTTPS origins (or localhost development pages). DOM scripts recheck the origin at execution to prevent cross-navigation races.

The extension never reads `input.value`, `textarea.value`, hidden field values, credentials, OTPs or payment data. Inspections omit forms and editable content and cap the returned visible text/elements. Typing is restricted to non-sensitive text and search inputs or safe textareas. Credential/payment form submission is explicitly blocked, as are script-link clicks. Discovered URLs omit query strings and fragments, which may contain OAuth tokens. **This is not a guarantee that all visible page content is non-sensitive:** the person approving the origin remains responsible for which site may be inspected.

The extension polls `http://127.0.0.1:18772/browser/poll` and replies to `/browser/result`. These handlers refuse requests arriving at the public MCP port, require a loopback peer and the same bearer, and do not grant CORS access to arbitrary websites. The MCP browser action queue has bounded capacity and 17-second expirations. No arbitrary JavaScript evaluation API exists.

### Important deployment prerequisites

The legacy SignalR extension still uses the pre-existing fixed `test_api_key` in `background.js`, and legacy Hub browser commands are outside the scope of this new MCP bridge. Do not assume that separate command path is publicly safe; harden/privatize it before any broader browser-control exposure.

Do **not** enable this feature on the live MCP without reconfiguring all current MCP clients to send the bearer (or disabling public ingress and using an authenticated private listener). The existing temporary Cloudflare tunnel and Tailscale Funnel must **not** be treated as authentication. With browser support enabled, the entire privileged MCP is bearer-gated; clients without that header will lose access. Do not expose the unauthenticated legacy Hub SignalR browser command channel as an alternative ingress. Review transport configuration separately before cutover.

Do not put the token in source code, Git, plugin descriptions, logs, URL parameters, or chat. If the token is compromised, rotate it in the server environment and extension popup (disable/re-enable with new token).

## Tools

| MCP tool | Inputs | Effect |
| --- | --- | --- |
| `browser_tabs` | none | Lists only approved tabs (id, sanitized URL, title, origin, active). |
| `browser_navigate` | `url`; either `newTab: true` or `tabId` | Navigates/creates only for approved destinations; an existing tab must itself be approved. |
| `browser_inspect` | `tabId` | Read-only visible text and safe interactive-element CSS selectors. |
| `browser_click` | `tabId`, `selector` | Clicks a non-sensitive target in the current approved origin. |
| `browser_type` | `tabId`, `selector`, `text` | Sets text on a non-sensitive editable control and dispatches input/change events. |
| `browser_activate` | `tabId` | Activates an approved tab without forcing Chrome into the foreground. |
| `browser_close` | `tabId` | Closes an explicitly identified approved tab. |
| `browser_reload` | `tabId` | Reloads an approved tab. |
| `browser_page_state` | `tabId` | Returns document-ready state, viewport/scroll metrics, origin and title; never form values. |
| `browser_scroll` | `tabId`, `deltaX`/`deltaY`, optional `selector` | Scrolls page or a safe element; offsets limited to ±2000 CSS px. |
| `browser_focus` | `tabId`, `selector` | Focuses a non-sensitive visible element without reading its contents. |
| `browser_find` | `tabId`, `text`, optional `maxResults` | Finds visible text, returning bounded snippets and selectors; excludes forms/editable fields. |
| `browser_wait_for` | `tabId`, `selector`, optional `timeoutMs` | Waits up to 8000 ms for a safe, visible element, checking permissions on each attempt. |
| `browser_select` | `tabId`, `selector`, `optionText` | Picks a non-sensitive native dropdown option by its exact visible label. |

All tools use the existing OmniMCP `/mcp` tool listing and `tools/call` conventions. No arbitrary script execution or full-page HTML scraping. Cross-origin links cannot be followed by click; use `browser_navigate` with an independently approved destination. History back/forward and raw screenshots are intentionally not offered: history targets cannot be preauthorized, and screenshots could expose sensitive forms.

## Local shadow-server validation (does not deploy or replace production)

Prerequisites: .NET 9 SDK, Node 22+, Python 3.13+.

```powershell
cd D:\SSDProjects\Tools\McpHelloProbe
dotnet publish McpHelloProbe.csproj -c Release -o published-v0.5.10-browser-dev
python TestFiles/test_browser_control.py published-v0.5.10-browser-dev/OmniMCP.exe

cd D:\SSDProjects\Omni\OmniSync.Chrome
node --check background.js
node --check browser-control.js
node --check browser-popup.js
node test-browser-control.cjs
```

For **a manually authorized local trial only**, generate a strong token in a private local PowerShell session. Do not store it in source, share it in chat, or place it in a command-line argument. The client and extension popup both need the same token:

```powershell
$pairingToken = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:OMNI_BROWSER_CONTROL_TOKEN = $pairingToken
# Display the token only in a local trusted terminal to provision the
# Chrome popup and the MCP client's HTTP Authorization header.
$pairingToken
$env:OMNI_BROWSER_CONTROL_ENABLED = '1'
$env:OMNIMCP_LITE_PORT = '18871'   # shadow server; does not replace 8771
$env:OMNI_BROWSER_BRIDGE_PORT = '18772'
& 'D:\SSDProjects\Tools\McpHelloProbe\published-v0.5.10-browser-dev\OmniMCP.exe'
```

The MCP client must use `http://127.0.0.1:18871/mcp` with an `Authorization: Bearer <token>` HTTP header. The Chrome extension's popup must be given **the same token** and explicitly enabled. Treat the terminal scrollback containing the token as sensitive, close the terminal after the trial, and rotate the token if disclosed. Grant each desired origin with **Allow current site's origin**; Chrome will show its own permission dialog. To test a site, open it in Chrome, then call `browser_tabs` and use the returned `tabId` for inspection and interaction.

**This source merge does not reload or publish the installed extension, change Chrome's profile, start a replacement service, alter Funnel/Cloudflare routes, or change any scheduled task.** After this merge, reload the existing unpacked extension from the canonical `D:\SSDProjects\Omni\OmniSync.Chrome` folder when ready. Enabling the production MCP bridge still requires coordinated authentication and separate approval.

## Validation scope and caveats

The integration test uses an isolated shadow OmniMCP process and a fake authenticated extension worker; it checks global authentication, local-only bridge port, tool advertisement, invalid arguments and command/result round-trip. Node tests check default-off behavior, denied/granted/revoked site permission, cross-origin race, DOM typing and redaction of sensitive form data. These validate the implementation without touching the user's running browser.

Not yet verified: actual Chrome MV3 service-worker longevity, real-site navigation/click/input behavior, loaded-extension host prompts and authenticated plugin configuration. The live Chrome and MCP services were deliberately not updated.

### Recorded checks (2026-10-17)

- `dotnet publish McpHelloProbe.csproj -c Release -o published-v0.5.10-browser-dev` — PASS.
- `python TestFiles/test_browser_control.py published-v0.5.10-browser-dev/OmniMCP.exe` — PASS (auth, local port boundary, catalog, request/response).
- `python TestFiles/test_robustness.py published-v0.5.10-browser-dev/OmniMCP.exe` — PASS (baseline malformed RPC, canceled command and 2,000 concurrent requests).
- `node test-browser-control.cjs` and `node --check` — PASS (permission allow/revoke, safe DOM actions, URL redaction, MV3 script syntax).
- The existing desktop test that captures a live user-screen screenshot was not run to avoid inadvertently capturing private screen content. No browser UI automation smoke was performed because installing/reloading the extension requires the user's explicit approval.
- Older Chrome media scripting on arbitrary sites will now require site grants because the prior blanket `<all_urls>` host permission has been moved to optional permissions; this is a deliberate least-privilege change to review before installation.

### Expanded browser tools validation (v0.5.10, 2026-10-17)

- Added nine tools to the original five: tab activate/close/reload; page state; bounded scroll; safe focus; visible-text find; bounded element wait; and native select dropdown interaction.
- Updated `TestFiles/test_browser_control.py` to verify all fourteen tool descriptions and input schemas, positive/negative argument checks, all nine new authenticated bridge round-trips, default-off advertisement, and global MCP authentication.
- Updated `test-browser-control.cjs` with simulated Chrome tab APIs and DOM coverage for all nine additional tools, revoked origins, unsafe cross-origin anchor clicks, and sensitive-field rejection.
- `dotnet publish ... -o published-v0.5.10-browser-dev`, `python TestFiles/test_browser_control.py published-v0.5.10-browser-dev/OmniMCP.exe`, `python TestFiles/test_robustness.py published-v0.5.10-browser-dev/OmniMCP.exe`, `node test-browser-control.cjs`, and JavaScript syntax checks: all **PASS** in isolated test environments.
- Running production OmniMCP remains `published-v0.5.8-sessions-filter/OmniMCP-Lite.exe`; no task or extension was changed.

### Real Chrome smoke test (2026-10-17)

A new isolated end-to-end script at `D:/SSDProjects/Tools/McpHelloProbe/TestFiles/test_browser_live_chrome.py` uses Playwright Chromium in a disposable browser profile, a synthetic localhost web page, a randomly generated temporary pairing token, and a standalone OmniMCP v0.5.10 shadow process. It **passed**, exercising the actual MV3 extension worker, pairing popup, localhost site allowlist, authenticated `browser_tabs`, `browser_inspect`, `browser_navigate` (existing and new tabs), `browser_page_state`, `browser_click`, `browser_type`, `browser_focus`, `browser_select`, `browser_find`, `browser_wait_for`, `browser_scroll`, `browser_reload`, `browser_activate`, and `browser_close`. Attempts to type in fake password and OTP fields were rejected. No real browser profile or sensitive page was read, and all shadow processes and the synthetic page server were stopped afterward.

During the pre-merge check, **Vivaldi Profile 3** referenced `D:/SSDProjects/Omni/OmniSync.Chrome` (which then contained manifest v1.0), while the test loaded v1.1 from the isolated worktree. **That discrepancy was resolved by merging v1.1 into the canonical directory.** Chrome's Default profile did not show a matching Omni extension in its extension metadata. At the time of the check, production OmniMCP remained v0.5.8 on 8771 with no bridge listener on 18772. To control real user tabs through OmniMCP, the extension must be reloaded from the canonical folder and the new server enabled with authenticated client configuration. No production MCP cutover or Chrome reload occurred as part of this source merge.
