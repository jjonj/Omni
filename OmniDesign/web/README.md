# OmniDesign shared web canvas editor — MISC-0163

This is the production editor source, not the OmniHub service. One React/TypeScript/Vite bundle serves desktop browsers and can be packaged unchanged into an Android WebView (MISC-0168); no separate Kotlin canvas or document model.

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

- OmniHub revision service/HTTP sync (MISC-0165), Android navigation/WebView packaging (MISC-0168) and agent proposals (MISC-0166) are separate tasks.
- Per-origin IndexedDB means devices do not share libraries until sync is implemented.
- Shared SVG pointer/gesture renderer, rather than React Flow internal state, keeps native grouped paths and typed ports in one coordinate model. This is the sole renderer for desktop and WebView.
- The default 1200×700 artboard sits on an unbounded panning workspace. SVG/PNG exports crop to the artboard; page bounds can be resized in Inspector when nothing is selected; advanced CAD behaviors are future work.
- Native contract/sample are in ../../docs/OmniDesign/. MISC-0163 evidence: E:/_AgentScratch/Tasks/Misc/MISC-0163/Evidence/.
