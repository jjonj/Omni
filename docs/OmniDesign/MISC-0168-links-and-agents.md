# MISC-0168 — Stable OmniDesign links and agent editing

## One identity, one native document

Every diagram has a permanent logical ID (`dgm_<uuid>`) minted before synchronization. The **Copy link** action in the desktop editor and the Android editor copies the same private OmniHub link format:

```text
https://hub.rancorous.online/omnidesign/?diagram=dgm_<uuid>
```

The remote Gateway's equivalent editor route is `/gateway/tools/omnidesign/?diagram=dgm_<uuid>`. The local service can open `http://127.0.0.1:8790/?diagram=dgm_<uuid>`. The ID is the identity, not the current revision, image, device, port, or filesystem path. Android can also handle `omnidesign://diagram/dgm_<uuid>` and supported HTTPS intents, using the **same bundled editor**. The HTTPS link is private: the existing OmniHub owner-login/Gateway path must be reachable; no new public ingress or authentication is added.

Copying a link to a locally created diagram does **not** upload it. Android/PC shows **local only/pending** until the existing sync pipeline accepts it. The ID and the link remain identical after synchronization. A phone with the cached diagram can open it offline, while an agent or a different device cannot fetch an unsynced ID from the PC. The editor keeps a visible warning with a retry action when a link is unknown, archived, or the PC is unreachable, rather than silently replacing the linked document with a successful-looking copy. Local edits and conflicts use IndexedDB and the existing outbox, not a new link database.

## Supported API / OMCP agent entry point

The service behind registered Gateway tool `omnidesign` and the browser editor both read the **same PC SQLite native JSON document**. To resolve a permalink, GET `/api/v1/links/resolve?link=<URL-encoded-link-or-ID>`. The response is the full `omnidesign` v1 document and a strong `ETag: "rev-N"`. No SVG/PNG or derived preview is considered the editable master.

The normal agent entry point from OMCP is the PC shell/command tool, invoking the thin API helper:

```powershell
cd D:\SSDProjects\Omni
py -B OmniDesign\service\agent_cli.py read 'https://hub.rancorous.online/omnidesign/?diagram=dgm_<uuid>' --output E:\_AgentScratch\Tasks\Misc\MISC-0168\Temporary\diagram.omd.json
py -B OmniDesign\service\agent_cli.py patch 'dgm_<uuid>' --revision 3 --operations E:\_AgentScratch\Tasks\Misc\MISC-0168\Temporary\operations.json
py -B OmniDesign\service\agent_cli.py propose 'dgm_<uuid>' --revision 3 --document E:\_AgentScratch\Tasks\Misc\MISC-0168\Temporary\proposal.omd.json --summary 'Add new state'
```

The helper defaults to `http://127.0.0.1:8790`, and if needed asks the **existing registered OmniHub Gateway** to launch its on-demand `omnidesign` tool, then waits for identity-specific health. Use `--base-url` with an already-authorized private proxy if running elsewhere; the helper does not authenticate a remote agent or bypass the owner login. `--no-launch` avoids starting the registered service. Standard agent work should use **propose** so the existing accept/discard API can review changes; use **patch** or native PUT for explicitly authorized direct writes.

Example `operations.json` for **shape-level** direct editing:

```json
{
  "operations": [
    { "op": "replace", "element": { "...": "complete native element with the same stable id" } },
    { "op": "add", "element": { "...": "complete valid native element with a new stable id" } },
    { "op": "remove", "id": "el_<uuid>" },
    { "op": "rename", "title": "Updated diagram" }
  ]
}
```

The `add`/`replace` payload is a complete native element, including its `type`, `id`, `layerId`, `parentId` and relevant geometry/ports/connectors; never send the abbreviated illustrative objects above to the service. Graph integrity is checked for all operations in a single SQLite transaction. To remove a node/group, include explicit removals/rewrites of dependent edges/children in the **same patch**. Invalid/dangling references reject the **entire batch**. Element IDs and existing unknown semantic fields survive unchanged unless the agent explicitly alters them.

Equivalent direct HTTP operation:

```text
PATCH /api/v1/diagrams/dgm_<uuid>/elements
If-Match: "rev-3"
Idempotency-Key: <stable-request-key>
Content-Type: application/json

{"operations":[...]}
```

Success returns the entire updated native document and `ETag: "rev-4"`; a single transaction creates the matching immutable revision snapshot. Replaying an identical request with the same key returns the stored receipt, with no extra revision. The helper uses a deterministic key from ID, base revision and operation payload when `--key` is omitted, so its retry is safe after an unknown response. Its proposal IDs are likewise derived from submitted content (or explicitly pinned via `--proposal-id`).

Existing operations also remain supported: `GET /api/v1/diagrams/{id}`, revision-history GETs, `PUT /api/v1/diagrams/{id}` with `If-Match` + idempotency key for full-native writes, and revision-pinned `POST /api/v1/diagrams/{id}/proposals`. **Agents always read an actual revision first, inspect and apply their changes to that base, and re-read/review if the Hub has moved.** No automatic last-writer-wins merge.

## Failure semantics

- **Unavailable / offline Hub:** connection failure or timeout; CLI exits nonzero, no success claim. Cached editor copy remains usable; remote agent cannot resolve an offline-only ID.
- **Unknown / not yet synced ID:** HTTP `404`, with an explanation. This is not proof a diagram was deleted on an offline phone.
- **Archived diagram:** HTTP `410` for linked open or writes; first restore it on the existing API. Archive/history stays recoverable.
- **Concurrent edit:** HTTP `412` and `currentRevision`/ETag on stale patch or PUT; no data mutation. Fetch the latest native document, inspect conflicts, deliberately revise/propose rather than silently overwriting.
- **Missing preconditions:** HTTP `428` for `If-Match` or `Idempotency-Key`. **Invalid graph:** HTTP `422`. **Duplicate/reused element ID or request key:** HTTP `409`.

## Implementation boundaries and verification

Sources: `OmniDesign/service/server.py`, `OmniDesign/service/agent_cli.py`, the shared `OmniDesign/web/src/{App,links,androidBridge}.tsx/ts`, and the existing Android Compose WebView/Activity link handler. No new attachment directory, image-only route, alternate diagram storage, realtime collaboration, or auth mechanism.

An isolated FastAPI/SQLite integration exercise at `E:/_AgentScratch/Tasks/Misc/MISC-0168/Temporary/verify_links.py` verified link/native document equality and ETag, one mutation + exact retry, stale 412, invalid graph 422, unknown 404, archive 410 and restoration, without touching production DB. Shared TypeScript frontend build and offline Android debug APK build were run. Physical Android link handling and authenticated remote Gateway routing are **not** claimed verified; the debug APK must be installed on a device to activate the native intent handling.
