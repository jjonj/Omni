# OmniHub Gateway — web tool launcher (2026-10-15)

The Gateway is an extensible **OmniHub workspace**, not solely a tool launcher. Notifications and information modules may be added later; currently only the web tool launcher is implemented.

## URLs

- Dashboard: `http://localhost:3333/gateway`
- Direct on-demand launch: `http://localhost:3333/<id>`
- Registered target: a tool-specific localhost URL (redirect once all health checks pass).
- API (local browsers only): `GET /gateway/api/tools`, `POST /gateway/api/launch/<id>`, `GET /gateway/api/log/<id>`. Registration is deliberately NOT exposed through the web server.

An unregistered root path returns 404; OmniHub's existing routes and static pages retain precedence. The launch page checks health, calls the single-flight launch endpoint only if necessary, waits for frontend **and** backend when both are configured, and navigates to the actual server. It does **not** reverse proxy applications or take over existing ports.

## Ownership and security

- **Registry source of truth**: `%APPDATA%\OmniSync\gateway-tools.json`, version 1, using camelCase JSON. This location is read by Hub and maintained via standalone OmniMCP-Lite. No legacy Hub MCP forwarding.
- **Launcher**: `GatewayToolManager` in `src/OmniSync.Hub/Logic/Services/Gateway`. Only Hub executes registered commands, does loopback HTTP health checks, coalesces concurrent starts, and logs to `%APPDATA%\OmniSync\gateway-logs\<id>.log`.
- **Registration**: OmniMCP-Lite `register_web_tool`, `list_web_tools`, `unregister_web_tool`. A tool registration includes `id`, `name`, `description`, `working_directory`, `executable`, `arguments` (array), `url`, `checks` (array of `{url, contains?}`) and optional `startup_timeout_seconds`. Registers are idempotent by ID and replace old definitions; unregistration does not kill processes. These MCP tools edit the registry locally, atomically, without proxying to the retired port 8770.
- The Gateway API is **only** active on 3333 and requires an actual loopback client. Launch POSTs also reject foreign Origin headers. No arbitrary shell commands can be submitted over HTTP. Static Gateway pages may be loaded through the Hub's existing web static middleware, but its APIs are local-only.
- Registry entries are trusted executable configuration from the signed-in Windows user/agent; do not expose writable registry paths to untrusted clients. Gateway registry URL validation accepts only loopback HTTP endpoints (no internet targets). Launching externally started servers does not transfer ownership or kill them.
- The registered health-check `contains` property is optional but recommended for frontend identity. Do **not** equate an unrelated process answering on the port with the intended application.
- The Gateway doesn't introduce idle shutdown, notifications, port interception, or a reverse proxy.

## First three tools

| ID | Directory | Launch | Frontend | Other health |
|---|---|---|---|---|
| `animation-lab` | `D:\SSDProjects\Tools\WartribesAnimationLab` | `py launch.py --no-browser` | `127.0.0.1:5178` | backend `127.0.0.1:8000/api/health` |
| `combat-sim` | `D:\Wartribes\Tools\CombatSimVisualizer` | `py -B server.py 8766` | `127.0.0.1:8766` | none |
| `settling-altraea` | `D:\SSDProjects\SettlingAltraea` | `py run_server.py` | `127.0.0.1:5173` | API `127.0.0.1:8787/api/health` |

Altraea's existing `run_server.py` intelligently restarts **project-owned** partial stacks and refuses unrelated port owners. Animation Lab launcher reuses responsive API/frontend processes, runs its scene seed, and remains their supervisor. The CombatSim host launches its standalone Python reader.

## Verification and operations

- Compile: `dotnet build OmniSync.Hub/src/OmniSync.Hub/OmniSync.Hub.csproj --no-restore`; use staging output when the current Hub executable is running.
- MCP smoke: `python TestFiles/test_gateway_registry.py published-v0.5.3-gateway/OmniMCP-Lite.exe` from McpHelloProbe. Uses an isolated temp registry and port.
- The Hub's deploy script `OmniSync.Hub/scripts/deploy_gateway_hub.ps1` backs up only Hub exe/dll/pdb, replaces them from staged builds, restarts the scheduled task, verifies the live dashboard API, and rolls back on failure. Avoid full repository clean/rebuild or overwriting concurrent source edits.
- An isolated registry path can be set via `OMNI_GATEWAY_REGISTRY_PATH` for tests; do not use the override in production.
- Probe `GET /gateway/api/tools` to see live status; view logs with `GET /gateway/api/log/<id>` if startup fails.
- Keep the Hub and OmniMCP-Lite binaries in versioned/backup locations for rollback.

Future design: the Gateway navigation can gain Notification Center, device/status summaries, queued jobs, and quick actions without changing how registered tools are launched.


## Gateway workspace improvements (2026-10-15)

The web dashboard at `/gateway/` is a dense launchpad (UI redesign 2026-10-15: the hero, metric cards, promo banner, filter chips, sort/list toggles and footer were removed on user request — launch buttons come first, no filler text):

- Shared top bar on Gateway, OmniSweep and the launch page: brand, Gateway/OmniSweep/Hub tabs, one search box, connection dot + last update time, refresh.
- Gateway shows three blocks of one-click buttons: **Tools** (tile = link to `/<id>`, colored monogram, status · port; pin and details/log buttons on hover), **Setups** (runs the OmniSweep setup via `POST /gateway/api/sweep/setups/{id}/run`; shows shortcut key and app names) and **Scenes** (voice-category macros). Setups/scenes come from `GET /gateway/api/sweep`; if that fails the blocks hide and tools still work.
- The search filters all three blocks; `Enter` activates the highlighted match, `↑/↓` moves the highlight, `Esc` clears, `/` or `Ctrl+K` focuses, `R` refreshes. Pins (sorted first) and recent opens stay in browser `localStorage` only.
- Per-tool details dialog: destination, error status, recent launcher log, copyable `/<id>` shortcut.
- Polling every 15s while visible, request timeouts, last known state kept across transient Hub outages.
- Phones (<760px) get single-column rows; touch devices always show the pin/details buttons.

The dashboard stays dependency-free and uses system fonts, so it loads without public CDNs.

### Backend hardening

- **Single-flight launch is actually single-flight**: the launch task is wrapped in `Lazy<Task<...>>`, because `ConcurrentDictionary.GetOrAdd` may invoke multiple value factories under contention. A shared completed task can be safely removed without removing the next flight.
- Health checks are bounded by two seconds **even when the server sends headers but stalls in the body**; streaming/partial HTML is read incrementally up to 128 KiB, matching identity markers that span chunks.
- A moved/unmounted tool working directory is shown as a tool-specific error instead of invalidating the entire registry. Launching it explains the missing path.
- Gateway route admission checks local loopback connection, HTTP port 3333, and an allowed local Host header to reduce DNS rebinding exposure. Launch API also enforces local Origin.
- Log permission errors no longer break an otherwise successful launch.

### Verify after changes

```powershell
node --check OmniSync.Web/www/gateway/gateway.js
dotnet build OmniSync.Hub/src/OmniSync.Hub/OmniSync.Hub.csproj --no-restore -p:OutDir='D:\SSDProjects\Omni\artifacts\gateway-verify\'
Invoke-RestMethod http://127.0.0.1:3333/gateway/api/tools
```

The HTML, CSS and JS are served directly from `OmniSync.Web/www/gateway` and update on refresh. Backend changes require deploying the new Hub DLL and restarting the Hub; do not replace an executing DLL without the existing backup/rollback deployment script. If other agents are using the Hub, prefer staging until a safe restart window.

### Verification record (2026-10-15, ~04:20 CEST)

- Live dashboard, JS, CSS and tool status endpoints served HTTP 200.
- Hub rebuilt successfully (no errors; existing unrelated build warnings remain) and was deployed via `scripts/deploy_gateway_hub.ps1 -SourceDirectory D:\SSDProjects\Omni\artifacts\gateway-verify`.
- Automatic pre-deploy backup: `OmniSync.Hub/src/OmniSync.Hub/bin/Debug/net9.0-windows/gateway-backup-20261015-041934`. Script verified Hub returned after restart.
- Live validation: all three original registered tools responding; shortcut routing and healthy launch worked; unknown tool -> 404, foreign launch Origin -> 403, foreign Host -> 404.
- `node --check` and `node OmniSync.Web/tests/gateway_ui_smoke.cjs` passed (DOM rendering, escaping, filters, counts and pins).
- Deliberately did not test cold-start by stopping any existing game/editor/web services; those live sessions remained untouched.


## OmniSweep integration (2026-10-15)

OmniSweep is merged into the single Gateway page at http://localhost:3333/gateway/ as vertical sections (Tools, Setups, Scenes, Macros, Files, Tailscale). `/gateway/sweep.html` is a redirect to `/gateway/#setups` kept for bookmarks.

**One shared config, not copies.** Desktop WPF OmniSweep and Gateway OmniSweep both use the live HubSettingsService.Settings singleton, persisted to %APPDATA%\OmniSync\settings.json. Desktop and browser edits go through the same existing HubSettingsService methods, and desktop OmniSweep's SettingsChanged subscription refreshes its interface immediately. The browser polls the Hub every three seconds while visible, automatically showing edits made through the desktop picker or Hub Settings. There is no second browser-owned config.

**Features:** List, search, run, create, rename, delete, reorder and edit setups (including launch step types and parameters); run real macros/scenes via IMacroService; search project roots via ProjectSearchService and open matching resources via ResourceOpenerService; show ongoing/next calendar entries from CalendarService. Existing captured window placement metadata remains intact when an existing action is edited. Revisions prevent stale browser forms overwriting recent desktop edits.

**Source:** Logic/Services/Gateway/GatewaySweepService.cs and GatewaySweepRoutes.cs in the Hub, OmniSync.Web/www/gateway/index.html + gateway.js + gateway.css for the browser (single page).

**API:** GET /gateway/api/sweep, GET /gateway/api/sweep/search?query=...; POST /gateway/api/sweep/setups, PUT /gateway/api/sweep/setups/{id}, POST /gateway/api/sweep/setups/{id}/delete, PUT /gateway/api/sweep/order; POST /gateway/api/sweep/setups/{id}/run, POST /gateway/api/sweep/macros/{id}/run, POST /gateway/api/sweep/search/open. Read-only endpoint exposes the Sweep-specific subset (not the entire settings file). All routes require port 3333, allowed localhost Host and loopback remote address; mutations additionally require same-origin Origin.

**UI:** setups are rows (drag handle or ↑/↓ to reorder, Edit, ▶ Run, ▣ re-capture), with each step shown as an app-name chip; scenes and other macros are run chips; the calendar is a one-line NOW/NEXT strip; file results appear only once the search has 2+ characters. New setups use an in-page name dialog and open the editor afterwards.

**Tests:** node --check OmniSync.Web/www/gateway/sweep.js; node OmniSync.Web/tests/sweep_ui_smoke.cjs; node OmniSync.Web/tests/gateway_ui_smoke.cjs; OmniSync.Hub/scripts/test_gateway_sweep.ps1. The live test creates/updates/persists/deletes a disposable setup, confirms revision conflicts and restores the original setup count without launching anything. An initial deployment exposed a minimal-API DELETE body binding startup error, fixed by POST deletion route. The corrected Hub was built, deployed and verified.

### Native capture integration and final verification

Gateway OmniSweep's **Capture windows** button and per-setup capture button invoke the existing native CommandDispatcher.RequestSetupSnapshot flow. Window selection is still performed by the native WPF capture experience; this avoids attempting to capture OS windows from browser JavaScript. Re-snapshot preserves setup actions by default.

The final deployed Hub build supports the snapshot route, and same-origin enforcement was exercised with a cross-origin snapshot POST returning 403 without starting capture. Full snapshot interaction was deliberately not triggered during unattended validation.

Live smoke after deployment: 3 original setups, 12 voice scenes, web assets 200, setup create/edit/persistence/stale-revision/deletion all passed, original setup count preserved. Automated frontend VM smoke and .NET build succeeded. The deployment script was hardened to stop scheduled relaunch attempts before replacing files and to verify the OmniSweep endpoint during startup, with rollback on failure.

## Phone / LAN access (2026-10-15)

Gateway and OmniSweep are available without authentication or pairing at http://<PC-LAN-IP>:3333/gateway/ and /gateway/sweep.html. Their GET and POST APIs operate on any client that can reach Hub web port 3333; this is intentional per user request. Do not reintroduce a localhost-only Host restriction or a pairing gate. The browser server continues binding port 3333 via ListenAnyIP. Note that port 3333 grants broad control over local programs, setups and macros; do not forward it from the router to the internet.

Root cause: direct LAN HTML/CSS/JS served correctly, but /gateway/ and both API groups returned HTTP 404 when using 192.168.1.37 as the Host and a non-loopback client. GatewayRoutes and GatewaySweepRoutes now enforce only the assigned Hub web-server port (3333), not a local address or Origin. Phone-oriented launch.html rewrites registered loopback server URLs to the hostname of the PC hosting Gateway, so compatible servers listening on all interfaces can open on the phone. Tool servers listening only on 127.0.0.1 remain inaccessible from the phone unless configured independently to bind to the LAN interface; this does not affect the Gateway itself.

Verification: deployed with scripts/deploy_gateway_hub.ps1, tested /gateway/ (redirect to HTTP 200 index), /gateway/api/tools (HTTP 200), /gateway/sweep.html (HTTP 200), and /gateway/api/sweep (HTTP 200) using the LAN IP. Existing frontend smoke tests and .NET build passed. 

## Tailscale overview (2026-10-15)

The Gateway page has a Tailscale section fed by `GET /gateway/api/tailscale` (`Logic/Services/Gateway/TailscaleStatusService.cs`). It runs only fixed `tailscale status --json` and `tailscale serve|funnel status --json` commands (5 s timeout, 10 s cache) and returns this PC's DNS name and IP, hosted Serve/Funnel URLs with their routes (Funnel = public internet, Serve = tailnet only), and online devices (Funnel ingress relays hidden). Browser input never reaches the command line. Until the Hub is redeployed the endpoint returns 404 and the section says to restart the Hub.

## Root redirect (2026-10-15)

On web port 3333, `GET /` returns a 302 to `/gateway/` (`GatewayRoutes.UseRootRedirect`, registered before static files in `Program.cs`). The Hub dashboard is still served at `/index.html`; Gateway's "Hub" link points there. Other ports (for example the 5055 Funnel target) are unaffected.
