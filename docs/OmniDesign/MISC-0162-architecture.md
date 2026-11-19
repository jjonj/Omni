# OmniDesign v1 — native document and platform contract (MISC-0162)

**Status:** design and machine-readable schema complete; **no production editor, service, sync or Android screen is implemented by this task**. Downstream work: MISC-0163 (editor), MISC-0165 (service), then Android/agent/integration tasks. Source owner is `D:/SSDProjects/Omni`.

## 1. Approved visual/interaction contract

MISC-0160 **R3 / A approved 2026-11-19 03:26:31Z**, MISC-0161 **R3 / A approved 2026-11-19 03:26:34Z**. Source: [approved R3 prototype](OmniDesign-r3-prototype.html) and [handoff](MISC-0161-design-handoff.md). R1/R2 are **not** the accepted direction. Approval covers mockup appearance and workflows, not deployed behavior.

| Approved decision | Contract consequence |
|---|---|
| Architecture-diagram skill's **1200×700** charcoal canvas, ivory solid boxes, dashed amber variants, labelled orthogonal links, restrained Segoe UI | Default artboard/page dimensions, theme tokens, node stroke/dash, label text, orthogonal routed edges in native document; `Export HTML figure` must reproduce that visual. Palette: `#14161a`, `#1d2026`, `#e6e2d8`, `#a09a8c`, `#e0a458`. |
| One canvas, **10 kits** | `family` = architecture, flowchart, sequence, state, erd, network, roadmap, floorplan, swimlane, mindmap. `game-system` and `custom` are also accepted vocabulary, not separate engines. Stencils/templates supply semantic roles, embedded shape libraries, and initial layout. |
| Desktop library with previews, agent strip, rail, contextual inspector, shape search, multi-select/align, layers, history and export | Persist actual graph, grouping/order, styles, semantics and shape identities. Library/preview/history UI is a projection, not source of truth. |
| Android compact top chrome, Canvas ⇄ Outline, bottom Select/Connect/Add/Undo/Redo, >=44px touch targets, bottom shape/edge sheets, zoom quick tools | Same web editor/model and gesture engine with adaptive chrome; do **not** fork a Kotlin-only diagram model or persist UI pixel positions in document. |
| Offline banner/pending count, offline create/edit, reconnect and default **Keep both** conflict resolution | Client-minted permanent document/element IDs, local transactional autosave and outbox, Hub integer revision check, explicit conflict-copy branch. |
| Agent handoff defaults to **Propose a revision**, with Current / Proposed visual diff and Accept / Discard | Agent reads native document, posts a full document proposal pinned to base revision; no silent overwrite by proposed edits. |

## 2. Canonical native source: `.omd.json`

Machine-readable **Draft 2020-12 JSON Schema**: [omnidesign.document.v1.schema.json](omnidesign.document.v1.schema.json); [working native example](example.architecture.omd.json). The example demonstrates a moved group with child node, versioned embedded shape, typed ports, cross-group orthogonal connection, text, path and R3 styling.

**One versioned document JSON is authoritative** across desktop, phone and agent edits. Do not save a React Flow graph, DOM tree, SVG, PNG or Mermaid as the sole editable master. Each root has:

- `format:"omnidesign"`, `schemaVersion:1`, stable `id:"dgm_<uuid>"`, Hub-assigned integer `revision`, title, `family`, metadata, artboard, theme tokens, ordered layers, typed `elements`, shape libraries and asset manifest.
- All persisted IDs are minted on the creating client via secure `crypto.randomUUID()`, with type prefixes `dgm_`, `dev_`, `lyr_`, `el_`, `prt_`, `itm_` (structured chip/list item), `ast_`; lowercase canonical UUID strings. **Never** replace the document ID on successful first sync. Element IDs need only be unique **within a document**; ports are unique inside their node and should remain stable across edits.
- Element discriminated union: `node` (shape library identity, concrete fallback primitive, ports, label and optional **structured editable title/subtitle/item list/chips/footer**, each list/chip with stable `itm_` ID), `edge` (typed endpoint, route and label), `group` (container transform), `text` (editable text and layout), `path` (native M/L/C/Z vector commands). Supports mockup-style multi-line panels, structured nodes and freehand/wall annotations in the same document. When `node.content` is present its title replaces the simple `label.text` in rendering; retain both fields for fallback and roundtrip.
- Edge endpoints are `port` (stable node/port IDs), `anchor` (node ID + side and normalized `t`), or `free` page/parent-local point. Ports have explicit `in|out|both` and optional `dataType`. `route.mode` supports orthogonal, straight and bezier; `waypoints` are **user-authored** geometry when `manual:true`, otherwise hints that the renderer may recompute.
- Every element records `layerId`, `parentId` (group or null), sibling `order`, visibility/lock flags as needed, style and semantic role/data. **All geometry, path commands and edge route waypoints are in the element parent's local coordinate system**, where null parent means page coordinates. Compose group transforms to obtain page coordinates. Port/anchor coordinates are relative to the node before transforms/rotation; attached edges update after node or ancestor motion. For cross-group edges, resolve endpoints through transforms into the edge's parent coordinates.
- `layers` array order controls back-to-front stacking; `order` controls siblings within one parent/layer. A group is a transform/bounds container and children reference it; `group` does not duplicate a `children` list. Unrelated edge routes are not secretly translated when another group moves.
- Per-element `style` (fill/stroke/dash/text/font/opacity/rounding) and `theme.tokens`; omitted style properties inherit named theme/library defaults. Render labels and text as true editable text, not flattened pixels. Data model deliberately does not store current pinch zoom, selection, undo stack, inspector state or connected status—these belong to **per-device editor state**.
- `libraries.references` identify versioned built-in packs and `libraries.embedded` carry versioned custom stencil definitions (primitive fallback, default style/ports) for portable offline reopening. Each node includes its concrete `shape.fallback` and fully persisted node label/style/ports so losing a library never destroys content. Never silently mutate an old library version. Components are reusable stencil instances in v1, **not** live-linked component instances across documents.
- `semantic.role` / `semantic.data` hold family-specific meaning (e.g. decision, lifeline, table, relationship, subnet, milestone, room, swimlane, game event). Kits produce typed base elements; semantic values must round-trip untouched. Optional `extensions` are namespaced (e.g. `omnidesign/layout`) and must be preserved unmodified by clients that do not understand them. Extensions cannot override base IDs, geometry or core version semantics.
- `assets` is a content manifest with stable asset ID, MIME, SHA-256, byte count. Raster image usage is **future extension**, not required as a core v1 element. Binary blobs travel by asset endpoints/cache, not absolute Windows/Android file paths; a portable package must include referenced blobs if/when image elements are supported.

**Validation invariants beyond JSON Schema** (implementation must enforce): document/element/layer/port/chip/list-item IDs unique in scope; all `layerId` references exist; all `parentId` references point to groups in the same document, without cycles; nested elements cannot claim different layers from their owning group; port endpoints point to existing **node** ports; anchor endpoints point to existing nodes; no dangling references on deletion (detach to `free` point or explicitly delete edge). Validate finite coordinates and bounded size/total document payload before persistence. Do not silently remove unknown elements/shape roles: for a future **schemaVersion** beyond the reader, open read-only and preserve original bytes until a deliberate migration is available. v1 migrations must be explicit and reversible through history.

**Revision semantics:** `revision:0` denotes an ID minted locally but never accepted by the Hub. First accepted create returns revision **1**. Every accepted server write increments once; local autosaves **do not** increment it. `metadata.updatedAt` is descriptive, **not** an overwrite/merge clock. The local outbox (deviceId, operation UUID, baseRevision, complete pending document) is **not** embedded in the shared document.

## 3. Minimal shared architecture and owned locations

```
React / TypeScript / Vite web editor (one renderer + commands + serialization)
    ├── PC browser → Gateway registered tool (same-origin API)
    └── Android Compose screen → bundled WebViewAssetLoader assets (usable offline)
                   ↳ native Kotlin narrowly scoped HTTP adapter to existing private Hub endpoint
            ↕ shared native OmniDesign JSON v1 + local IndexedDB outbox
On-demand OmniDesign HTTP service (Python FastAPI/Uvicorn + stdlib sqlite3)
            ↕ SQLite WAL documents, revision snapshots, proposal records + asset cache
Existing Rust OmniHub Gateway registered web tool + existing private remote gate
Agent → same JSON REST API → proposed revision / diff → human accept
```

**Framework choice grounded in inspected source (2026-11-19):**
- `D:/SSDProjects/Omni/OmniSync.Android` is **Kotlin + Compose**; existing `WebServerScreen.kt` and `EpubViewerScreen.kt` already use `AndroidView(WebView)`. Its Gradle app already declares `androidx.webkit:webkit:1.11.0`. Add a dedicated OmniDesign Compose nav screen wrapping the **same built Vite bundle** via `WebViewAssetLoader` on `https://appassets.androidplatform.net/omnidesign/`, with JavaScript/DOM storage and persistent WebView data. This allows opening/creating diagrams even before first successful Hub connection, unlike a remote-only WebView.
- The Rust OmniHub service is owned by `D:/SSDProjects/Tools/Omnish/main`, not the retired C# OmniSync.Hub; its `gateway_tools.rs` already launches registered loopback HTTP tools using configured executable, working directory, URL and health checks, and `/gateway/tools/<id>/` supports private remote HTTP/WebSocket through the existing login gate. **No Rust Hub fork, new external ingress, Cloudflare route, new auth, always-on process or mandatory WebSocket collaboration needed.**
- Desktop UI stack: **React + TypeScript + Vite**, aligned with the Omnish React/Vite tooling. Choose **`@xyflow/react`** for dragging/zooming/selecting typed connected nodes/edges, with custom node/edge renderers and a shared coordinate-aware SVG layer for editable text/freehand/plan primitives. Keep the schema/operations **independent** from React Flow internal node/edge state so that platform and library changes do not mutate file compatibility. Build responsive desktop inspector/rail vs mobile sheet/outline on top of the same model.
- Service stack: **Python FastAPI/Uvicorn + builtin SQLite** (both FastAPI/Uvicorn present on PC at design time); one small on-demand HTTP process serves built editor assets, REST endpoints and `/api/health`. Store runtime data under `%LOCALAPPDATA%/OmniHub/OmniDesign/` (SQLite WAL, snapshots, asset directory), not in a Git checkout, app bundle or GDrive. A separate SQLite database service and additional permanent daemon are unnecessary.
- Proposed source placement: `D:/SSDProjects/Omni/OmniDesign/{web,service,shared}` under the existing **Omni** Git repository; final server executable/port are bound only at implementation and registered through the existing `register_web_tool` workflow as **`omnidesign`**, with identity-specific `/api/health` check. Keep private by default; do not reconfigure existing Gateway runtime during this design task. Gateway's launch state handles startup and health. Pin one loopback listener after checking existing port ownership.

**Android transport boundary:** appassets WebView and remote private Gateway are different origins; do not assume cross-site cookies/CORS will work. A tiny request/response bridge exposes only the OmniDesign `/api/v1/` contract to JS and sends HTTP using the app's existing configured Hub connection/credentials. Return async results to WebView by request ID, not unrestricted shell/file methods. Desktop uses same-origin relative fetch at the registered Gateway tool path. No new login protocol is defined here. The actual adapter must be verified from phone on LAN/Tailscale before claiming remote/offline parity.

## 4. HTTP + agent contract for MISC-0165 and later

Use JSON UTF-8; all paths below are relative to the registered OmniDesign service origin. Behind the Gateway the public/private route is `/gateway/tools/omnidesign/` and all editor calls must be **relative** to that base. Do not hardcode `localhost` into browser assets.

| Operation | Path | Meaning |
|---|---|---|
| `GET` | `/api/health` | `{"service":"omnidesign","status":"ok","schemaVersion":1}`, specific enough to verify process identity |
| `GET` | `/api/v1/diagrams?cursor=...` | paged IDs/title/family/revision/updatedAt, archive state, no giant embedded graphs |
| `POST` | `/api/v1/diagrams` | full document with offline-minted ID and revision 0; reject duplicate ID with different content, return revision 1 |
| `GET` | `/api/v1/diagrams/{id}` | full native document + strong `ETag: "rev-N"` |
| `PUT` | `/api/v1/diagrams/{id}` | full versioned replacement with `If-Match: "rev-N"` and `Idempotency-Key` per locally queued save; validated in one SQLite transaction, return next revision + ETag |
| `GET` | `/api/v1/diagrams/{id}/revisions` and `/.../revisions/{N}` | history metadata and read-only historical snapshot to show/recover visual diff |
| `POST` | `/api/v1/diagrams/{id}/proposals` | agent submits `{proposalId,baseRevision,document,summary}`; persist an **immutable** proposal, not replace current doc |
| `GET` | `/api/v1/diagrams/{id}/proposals` | pending and resolved proposals; compare current/proposed by stable element IDs |
| `POST` | `/.../proposals/{proposalId}/accept` | human-approved accept, revision-checked against **current** doc; if stale, show diff/rebase choice |
| `POST` | `/.../proposals/{proposalId}/discard` | explicit discard, keep audit/history |
| `POST` | `/api/v1/diagrams/{id}/archive` | soft archive (library hides until recovered); no immediate data loss |
| `GET/POST` | `/api/v1/assets/{assetId}` | future asset fetch/upload by content identity; not required for core text/vector milestone |

A stale `If-Match` returns **412 Precondition Failed** with current revision/ETag; invalid graph returns **422** with field errors; duplicate client document ID differing in content returns **409**; unknown ID returns **404**. Required precondition absent on PUT returns **428**. Persist received `Idempotency-Key` with response/revision so retries after timeout do not double-apply. Creation and proposal submission likewise use stable client-generated IDs for retry deduplication. Agent direct `PUT` is permitted only when explicitly requested by the human; normal agent actions make proposals and use the same schema/revision rules.

**Stable links:** use ID-based editor URL `/gateway/tools/omnidesign/?diagram=dgm_<uuid>` and the same ID in Android's OmniDesign navigation intent. Link is private and resolves via the currently configured Hub/Gateway origin; do not store machine/domain/origin URLs in documents. An offline-created ID can be copied immediately, but UI must clearly say **not accessible on other devices until first sync**. The history route uses ID + revision to produce a read-only view; the canonical link always follows latest accepted revision.

## 5. Offline, conflicts and lifecycle

1. **Every edit is local first:** write a full valid working snapshot and its pending outbox record in **one IndexedDB transaction**, then update canvas. Autosave is not merely a debounce/network request. Mirror locally opened synced documents, local-created documents, and pending proposals. Per-device pan/zoom/selection/undo stack are independent; only native graph is synced. A storage failure must be surfaced as unsaved, never shown as locally saved.
2. **A single outbound writer per diagram** uses the latest complete local snapshot, `baseRevision` from last acknowledged Hub revision, stable mutation UUID, and conditional HTTP. Coalesce superseded pending local edits *before* sending; never lose an edit that happened during an in-flight save. On successful acknowledgement, advance base revision and remove only the acknowledged journal entry; send a newer snapshot if present.
3. **On reconnect**, verify Hub identity and fetch current revision before first write. Backoff transient failures without dropping pending data; keep pending count visible (approved R3). A timeout's outcome is unknown: retry with **the same** idempotency key, or query/reconcile receipt; do not generate a fresh write key that duplicates an accepted mutation.
4. **412 = branch, not last-writer-wins.** Save local unsynced snapshot under its existing ID in the local cache; show current/proposed visual diff. Default button **Keep both**: create a **new globally minted diagram ID** with `metadata.forkedFrom:{id:<original>,revision:<base revision>}`, copy native elements/libraries/asset refs without changing internal element IDs, persist fork locally first, then upload fork at revision 0. Original remote stays intact. UI can then show both names (e.g. `· conflict copy`). Other explicit choices: discard local after preserving rollback snapshot, or overwrite remote only after the user sees current remote and explicitly confirms a new revision-checked PUT. Never silently merge even disjoint edits in v1.
5. **Agents** ordinarily submit proposals; a stale proposal remains viewable but cannot be accepted without inspecting the new current revision. Human accept creates a regular Hub revision. The agent proposal is not an alternate native document format.
6. **PC browser offline:** cache last-used static editor shell (HTTPS/localhost service worker where supported), IndexedDB working snapshots/outbox; offline use requires the editor shell to have loaded at least once. **Android:** APK-bundled shell works on first launch without Hub. Use the same IndexedDB schema/journal semantics; do not rely on Android's existing FilesViewModel text-file SharedPreferences cache for graphical document durability.
7. **Crash/restart:** SQLite WAL transaction + revision snapshots survive service restart; IndexedDB survives WebView/activity restart; pending outbox survives process death. No live multiuser presence, locks, CRDT/OT, operational-transform server, shared cursors or always-on websocket required for v1. Multi-device concurrent edits are handled by branch-on-conflict.

## 6. Import/export fidelity contract

| Format | Direction | Guaranteed result / known loss |
|---|---|---|
| **Native `.omd.json`** | full import/export | Lossless graph/IDs/geometry/routes/ports/roles/style/libraries/extensions for matching schema version. Import as copy must mint new diagram ID, retain element IDs; explicit restore may preserve ID with revision handling. Exported revision is historical metadata, not automatic permission to overwrite server. |
| **SVG** | export + best-effort import | Export retains visual vector/text positions but **not** guaranteed native ports, group semantics, revision history or semantic kit roles. Import supported primitives/text/paths into native nodes/paths as new IDs; unsupported filters/fonts/transforms may rasterize or warn. Embedded OmniDesign metadata may improve own-export reimport, but SVG is never the master. |
| **PNG** | export + background/reference import later | Flat raster image with fixed resolution, no editable graph. Native import of pixel image alone is not interpreted as nodes/connectors. |
| **Mermaid** | export + limited import | Export only compatible flowchart/sequence/state/ERD subsets, potentially losing exact pixel layout, custom library shapes, styles, freehand paths, multi-layer/group specifics. Import generates editable native nodes/edges with fresh IDs and automatic layout, **not** original geometry; warn on unsupported syntax. |
| **HTML figure** | export | R3 architecture-skill style presentation preserving visible appearance for sharing; source remains `.omd.json`; figure is not collaborative/editable source. |

Exports are **derived** from saved native data, never used as backing storage. Warn before any lossy import and allow preview; don't advertise a generic SVG/Mermaid roundtrip as lossless.

## 7. Task boundary / implementation checklist

- **MISC-0163 (web editor):** build shared React/TS graph editor and native schema serializer/validator, adapt R3 desktop + Android-sized chrome, fit/zoom/ports/selection/group/layers/undo, IndexedDB atomic snapshots, native load/save and export. Visual parity must follow **approved R3**, not R2.
- **MISC-0165 (service):** FastAPI + SQLite WAL service, immutable version history, proposal/accept/diff contract, revision CAS + idempotency, on-demand Gateway registration, stable URLs, health identity and storage path. Verify PC cold-start/Hub reconnect after implementing; update Omni Registry service entry **once implemented**.
- **Android follow-up:** package identical built editor in Android assets, Compose navigation + WebViewAssetLoader, scoped Kotlin transport bridge, offline first launch, bidirectional deep links, physical phone sync/conflict flow. Do not create a parallel Compose canvas editor.
- **Agent follow-up:** teach agent tools to retrieve/validate native documents and create revision-pinned proposals; current/proposed diff/approval UI in same editor. Avoid replacing the document with Mermaid/SVG.
- **Registry:** Existing [Omni Registry](B:/GDrive/ProjectsG/Omni%20Registry/README.md) catalogs Omni/Omnish and Gateway infrastructure; v1 design is **planned only**, so do not publish it as a running service. This document is the handoff until service activation.

**Deliberately deferred:** real-time collaboration and CRDT, live-linked library instances, CAD-grade dimensions/constraints, embedded binary/raster editing, unbounded history policies, multi-user account/permissions system, cloud database, image-to-diagram reconstruction. These do not block native editable v1 or its offline/conflict semantics.
