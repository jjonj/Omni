# OmniMCP browser control (development candidate)

Status (2026-10-17): **implemented and tested in an isolated branch; NOT installed, deployed, published or enabled in the running Chrome / OmniMCP service.**

## Repositories and ownership

- Chrome Manifest V3 extension: `D:\SSDProjects\Omni\OmniSync.Chrome` (existing `Omni` Git repository). To avoid conflicts with a heavily modified shared worktree, these changes live in Git worktree `D:\SSDProjects\Omni-browser-control`, branch `feature/browser-control-20261017`. Existing SignalR/Android/Hub browser commands remain separate.
- Supported, standalone OmniMCP source: `D:\SSDProjects\Tools\McpHelloProbe` (not a Git repository). New source `BrowserControlTool.cs`, minor `Program.cs` changes; originals backed up at `backups/browser-control-20261017`. Production scheduled task still points to the older published executable and was NOT modified.
- Rust OmniHub successor in `D:\SSDProjects\Tools\Omnish\main` is not the owner of the supported MCP endpoint. Its legacy embedded MCP listener at 8770 is retired. No bridge or proxy to it is needed.

## Security model

Browser control is **disabled by default**. OmniMCP exposes its five browser tools only when both `OMNI_BROWSER_CONTROL_ENABLED=1` and a strong `OMNI_BROWSER_CONTROL_TOKEN` (32+ characters) are configured in the **process environment**. When enabled, **every request to `/mcp`** (including existing shell and filesystem tools and initialization) must have `Authorization: Bearer <token>`; unauthenticated public clients fail closed with HTTP 401. This global guard is essential because the existing publicly reachable MCP service otherwise exposes privileged shell tools that could circumvent a browser-specific token.

The extension independently requires a matching pairing token and an explicit enabled toggle. Its popup obtains site host permissions through a direct Chrome user gesture. The worker also checks a separately stored per-origin allowlist and the Chrome host-permission grant for **each action**; unapproved tabs do not appear in discovery, and navigation is restricted to approved HTTPS origins (or localhost development pages). DOM scripts recheck the origin at execution to prevent cross-navigation races.

The extension never reads `input.value`, `textarea.value`, hidden field values, credentials, OTPs or payment data. Inspections omit forms and editable content and cap the returned visible text/elements. Typing is restricted to non-sensitive text and search inputs or safe textareas. Credential/payment form submission is explicitly blocked, as are script-link clicks. Discovered URLs omit query strings and fragments, which may contain OAuth tokens. **This is not a guarantee that all visible page content is non-sensitive:** the person approving the origin remains responsible for which site may be inspected.

The extension polls `http://127.0.0.1:18772/browser/poll` and replies to `/browser/result`. These handlers refuse requests arriving at the public MCP port, require a loopback peer and the same bearer, and do not grant CORS access to arbitrary websites. The MCP browser action queue has bounded capacity and 17-second expirations. No arbitrary JavaScript evaluation API exists.

### Important deployment prerequisites

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

All tools use the existing OmniMCP `/mcp` tool listing and `tools/call` conventions. No arbitrary script execution or full-page HTML scraping.

## Local shadow-server validation (does not deploy or replace production)

Prerequisites: .NET 9 SDK, Node 22+, Python 3.13+.

```powershell
cd D:\SSDProjects\Tools\McpHelloProbe
dotnet publish McpHelloProbe.csproj -c Release -o published-v0.5.9-browser-dev
python TestFiles/test_browser_control.py published-v0.5.9-browser-dev/OmniMCP.exe

cd D:\SSDProjects\Omni-browser-control\OmniSync.Chrome
node --check background.js
node --check browser-control.js
node --check browser-popup.js
node test-browser-control.cjs
```

For **a manually authorized local trial only**, generate a strong token in the current PowerShell session without printing or persisting it:

```powershell
$env:OMNI_BROWSER_CONTROL_TOKEN = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:OMNI_BROWSER_CONTROL_ENABLED = '1'
$env:OMNIMCP_LITE_PORT = '18871'   # shadow server; does not replace 8771
$env:OMNI_BROWSER_BRIDGE_PORT = '18772'
& 'D:\SSDProjects\Tools\McpHelloProbe\published-v0.5.9-browser-dev\OmniMCP.exe'
```

The MCP client must use `http://127.0.0.1:18871/mcp` with an `Authorization: Bearer <token>` HTTP header. The Chrome extension's popup must be given **the same token** and explicitly enabled. Grant each desired origin with **Allow current site's origin**; Chrome will show its own permission dialog. To test a site, open it in Chrome, then call `browser_tabs` and use the returned `tabId` for inspection and interaction.

**Do not load this worktree as an unpacked extension, reload the installed extension, modify Chrome's extension profile, start a live replacement service, alter Funnel/Cloudflare routes, or change the scheduled task without explicit user approval.** The manual browser installation / smoke check and live gateway integration remain pending approval.

## Validation scope and caveats

The integration test uses an isolated shadow OmniMCP process and a fake authenticated extension worker; it checks global authentication, local-only bridge port, tool advertisement, invalid arguments and command/result round-trip. Node tests check default-off behavior, denied/granted/revoked site permission, cross-origin race, DOM typing and redaction of sensitive form data. These validate the implementation without touching the user's running browser.

Not yet verified: actual Chrome MV3 service-worker longevity, real-site navigation/click/input behavior, loaded-extension host prompts and authenticated plugin configuration. The live Chrome and MCP services were deliberately not updated.
