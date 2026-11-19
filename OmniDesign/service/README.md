# OmniDesign PC service (MISC-0165)

OmniDesign is an **on-demand, PC-hosted FastAPI/SQLite service** managed by the existing Rust OmniHub Gateway. It serves the **same** React/Vite editor as desktop/Android and the native, revisioned `.omd.json` API for browser and agent clients. No extra database daemon, public ingress, live collaboration or new authentication system.

## Registered runtime

- **Gateway ID:** `omnidesign`, registered through OMCPT `register_web_tool`; launch via `http://localhost:3333/omnidesign` (Gateway tile) or `/gateway/launch.html?tool=omnidesign`.
- **Private remote entry:** `https://hub.rancorous.online/omnidesign` → existing private owner-login gate → `/gateway/tools/omnidesign/` on the remote host. Do not mark this web tool public: it contains write APIs. A local Gateway on `:3333` redirects to the direct loopback server rather than offering `/gateway/tools/*` itself; the private Node gate provides the **remote** HTTP proxy. Asset links and API fetches use relative URLs in both contexts.
- **Executable:** `py -B -m uvicorn server:app --host 127.0.0.1 --port 8790 --no-access-log`, working directory `D:\SSDProjects\Omni\OmniDesign\service`; check `http://127.0.0.1:8790/api/health` contains `"service":"omnidesign"`. Gateway owns on-demand launching and readiness; no separate Task Scheduler task.
- **Dependencies:** Python 3, FastAPI, Uvicorn, jsonschema; install with `py -m pip install -r requirements.txt` when provisioning the PC. Build frontend from `../web` with `npm run build` (rebuild after editor changes). The service serves `../web/dist` including static assets; in its absence the REST API still starts but `/` reports `editor-not-built`.
- **Durable data:** `%LOCALAPPDATA%\OmniHub\OmniDesign\diagrams.sqlite3` plus `-wal`/`-shm` while open, **outside Git/GDrive**. SQLite WAL transactional writes and immutable per-revision JSON snapshots. Backup while running via SQLite online backup, not by copying only the `.sqlite3` file while WAL is active. `OMNIDESIGN_DATA_DIR` overrides storage for isolated runs.

The registration is deliberately **private and on demand**. Gateway starts/restarts the process on opening the tool, and checks the identity-specific health URL. Windows PC restart requires OmniHub to be running first; the existing Gateway shortcut then relaunches OmniDesign using the persisted SQLite data. Live auth, DNS and Android WebView packaging are owned by existing or follow-up tasks, not this service.

## Native API

All JSON bodies are the entire native v1 document (not SVG, React Flow nodes or a flattened image); base prefix is `/api/v1`. Use the **same origin as the editor**, with a relative base `./api/v1/`:

| Request | Behavior |
|---|---|
| `GET /api/health` | Identity and schemaVersion; reads SQLite |
| `GET /diagrams?cursor=&limit=50&includeArchived=false` | Keyset-paged metadata (`items`, `nextCursor`) |
| `POST /diagrams` | Client-generated `dgm_<uuid>` at revision 0 → stored revision 1; exact duplicate POST is idempotent |
| `GET /diagrams/{id}` | Full editable source, strong ETag `"rev-N"` |
| `GET /links/resolve?link=<url-or-id>` | Resolve a private permanent diagram link/ID to the **same native document** and ETag; 404 unsynced/unknown, 410 archived |
| `PATCH /diagrams/{id}/elements` | CAS/idempotent native shape-level `add`/`replace`/`remove` and document `rename` operations; full graph validation in one transaction |
| `PUT /diagrams/{id}` | Full document with body revision N, `If-Match: "rev-N"`, `Idempotency-Key: <uuid>` → revision N+1 |
| `GET /diagrams/{id}/revisions` | History metadata; `GET /.../revisions/{N}` returns immutable snapshot |
| `POST /diagrams/{id}/proposals` | `{proposalId,baseRevision,document,summary}`, immutable pending suggestion |
| `GET /diagrams/{id}/proposals` | Suggestion summaries; `GET /.../proposals/{proposalId}` returns proposed document |
| `POST /.../proposals/{proposalId}/accept` | Applies only when proposal base equals current revision; creates new revision |
| `POST /.../proposals/{proposalId}/discard` | Rejects suggestion, retains history |
| `POST /diagrams/{id}/archive`, `POST /.../restore` | Hide/recover without deleting source/history |
| `GET /diagrams/{id}/export` | Native `.omd.json` file; SVG/PNG exports remain in browser |

Returns `422` for invalid document, dangling IDs/groups/ports or unknown schema, `409` for duplicate/conflicting stable IDs, `428` for missing write preconditions, and `412` (including `currentRevision` and ETag) for stale writes. Idempotency receipts are durable and replays do not advance the revision again. Rejected operations never mutate database rows. Agent clients should **propose**, not silently replace, unless directly requested. A human's accept acts on the proposal endpoint; no claim of additional permission checks is made.

## Permanent links and agent editing (MISC-0168)

The editor and Android client both copy a private ID-stable link (`https://hub.rancorous.online/omnidesign/?diagram=dgm_<uuid>`), including for diagrams minted offline. Until first synchronization, that link is **not** remotely dereferenceable. Browser/Android deep links open the native cached/Hub revision, keep local edits, and explicitly report unavailable/unknown/archived cases. The Python CLI `agent_cli.py` lets agents via OMCP run_command resolve/read, revision-check and submit shape patches or reviewable native proposals against **this same service**; the CLI uses the registered Gateway on-demand launch when necessary. See [MISC-0168 agent contract](../../docs/OmniDesign/MISC-0168-links-and-agents.md). No alternate document store or image routes were created.

## Browser workflow and limitations

The shared editor exposes **Hub save**, **Hub library**, and **Link** actions on PC and Android. Local IndexedDB saving continues to run independently; uploads are explicit so editing offline does not risk silent overwrite. On conflicts, the Hub refuses the stale revision and preserves the local copy. Client-side IndexedDB outbox/reconnect and Keep-both conflicts are implemented by MISC-0167 while the Android editor is visible; richer visual proposal diff/approval UX and agent MCP wrappers remain downstream work; REST clients can already perform these operations today.

Deep link format (private, ID-stable): `/gateway/tools/omnidesign/?diagram=dgm_<uuid>` on the remote host. Local share through `http://localhost:3333/omnidesign` first opens Gateway's launcher; use the service's direct `http://127.0.0.1:8790/?diagram=...` for loopback bookmarks. Deep link requests recover the native document from PC if reachable, else from IndexedDB when available.

## Verification (2026-11-19)

- Sample v1 schema + semantic graph accepted by server; isolated SQLite exercise verified create/list/get, revisioned CAS write and stale-412, proposal accept, history, soft archive/restore, native export, and reinitialization from saved disk state.
- `npm run build` succeeds for the updated shared editor.
- OMCPT registration was applied to the live Gateway; `POST /gateway/api/launch/omnidesign` started its server and direct loopback `:8790/api/health` returned `{"service":"omnidesign","status":"ok","schemaVersion":1}`. A real revision-1 diagram was created through HTTP, the verified OmniDesign listener process was terminated, Gateway relaunched the service, the same diagram ID/revision/title was read back successfully, then the fixture was archived.
- **Not tested:** rebooting Windows itself, private remote owner-login routing, physical Android, or multi-device offline outbox merge. Gateway local `:3333/gateway/tools/omnidesign/` is intentionally **not** a reverse proxy: only the remote Node gate serves that path. Do not treat a local 404 there as a service failure.

## Ownership

Source + runtime schema: `D:/SSDProjects/Omni/OmniDesign/service/server.py`, `docs/OmniDesign/omnidesign.document.v1.schema.json`. Browser client: `OmniDesign/web/`. Rust Gateway manager: `D:/SSDProjects/Tools/Omnish/main/src-omnihub-tauri/src/gateway_tools.rs` (reused unchanged). Live tool configuration: existing shared Gateway registration, not the Omni Registry markdown or service code. Registry: `B:/GDrive/ProjectsG/Omni Registry/services.md`.
