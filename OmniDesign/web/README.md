# OmniDesign shared web canvas editor — MISC-0163

This is the production editor source, not the OmniHub service. One React/TypeScript/Vite bundle serves desktop browsers and the APK-bundled Android WebView (MISC-0166); no separate Kotlin canvas or document model.

## Run and bundle

From D:/SSDProjects/Omni/OmniDesign/web:

~~~sh
npm ci
npm run dev
npm run build
~~~

dist/ is self-contained with relative asset URLs for a Gateway subpath or WebViewAssetLoader. Use a local HTTP origin; file:// does not guarantee IndexedDB support.

## Editing

- Pan by dragging blank space or with Hand; scroll/pinch to zoom around the pointer; Fit/+/- controls.
- Select and drag shapes; Shift/Ctrl click for multiple selection; drag the bottom-right handle to resize; toggle grid/snap.
- Connect mode: touch/click source and destination ports. Stable native port IDs persist; attached connectors follow node/group movement.
- Insert rectangle, panel, ellipse, diamond, text and freehand strokes using the left rail / Android Add sheet. Edit text, structured node items/chips, colors, sizes, connector labels and routing in Inspector.
- Group/ungroup shapes/text/freehand paths, add/reorder/lock/show layers, and use Outline to select elements.
- Undo/redo, Ctrl+Z / Ctrl+Y, Ctrl+G group, Delete remove; Ctrl+S downloads native source.
- Local diagrams autosave to per-origin IndexedDB. Library opens another diagram; Import validates .omd.json against the existing v1 schema and referential invariants. Import collision prompts replace or copy.
- Native .omd.json is authoritative editable model. SVG and 2× PNG are exports only. Semantic data, embedded libraries, extensions, authored waypoints and group-local geometry survive edits.
- Unknown future schema versions are rejected without rewriting them.

## Boundaries

- MISC-0165 implements the live on-demand OmniHub SQLite revision service and explicit **Hub save**, **Hub library** and **Link** actions. The browser keeps a local IndexedDB copy, while Hub writes use conditional revisions and refuse stale writes. See [service API/operations](../service/README.md).
- MISC-0166 bundles the same editor into Android WebView, with durable offline IndexedDB. MISC-0167 adds reconnect/resume synchronization while the Design screen is visible, paginated PC downloads, transactional outbox/idempotent retries and durable Keep-both conflict resolution (see ../../docs/OmniDesign/MISC-0167-sync.md). Agent-side proposal/diff review remains separate work; local source diagrams remain editable offline.
- Shared SVG pointer/gesture renderer, rather than React Flow internal state, keeps native grouped paths and typed ports in one coordinate model. This is the sole renderer for desktop and WebView.
- The default 1200×700 artboard sits on an unbounded panning workspace. SVG/PNG exports crop to the artboard; page bounds can be resized in Inspector when nothing is selected; advanced CAD behaviors are future work.
- Native contract/sample are in ../../docs/OmniDesign/. MISC-0163 evidence: E:/_AgentScratch/Tasks/Misc/MISC-0163/Evidence/.

## Stencil packs and templates (MISC-0164)

- **Templates** creates fully editable examples for architecture, flowchart, UML sequence, UML state, ERD, mind map, timeline, network topology, game design, floorplan, swimlane/process and wireframe (12). The original document is not replaced.
- **Kits** opens the searchable mixed-library symbol palette. Pick a stencil and tap the shared canvas; use regular drag/resize/ports, inspector, layer and undo tools. Choose connector semantics before connecting or change an existing edge in Inspector.
- Specialized properties are persisted in native `semantic.data`, not only text labels. Built-in stencils are versioned; importing a custom `.omdpack.json` adds more symbols and templates with the same canvas, and used custom packs embed portable fallback definitions and rich extension metadata.
- Sample custom pack: `public/sample.quest-pack.omdpack.json` (also downloadable through Kits). Specification and all 12 family examples: `../../docs/OmniDesign/MISC-0164-packs.md`.

The diagrams are editable visual models, not formal code generators or CAD. All kits remain freeform on the same document, including cross-kit connectors.
