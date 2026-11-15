# MISC-0161 — OmniDesign Android / offline interaction review

**Status:** Proposed UI, awaiting native TaskBoard visual approval. This work changes no Android, desktop editor, OmniHub, or sync implementation. Interactive preview: `MISC-0161-interactions.html` (registered with OmniHub Presentations as `misc-0161-omnidesign-interactions`).

## Review the five guided flows

1. **Library:** search, open, choose shape/diagram vocabulary, and manage (rename, duplicate, archive) across phone and desktop.
2. **Draw & edit:** select/drag graph nodes; tap-to-place shapes; connector selection; pinch/wheel zoom; native-style mobile property bottom sheet vs. desktop inspector.
3. **Offline:** show offline status, create a local diagram with a stable ID, edit while disconnected, and expose queued changes in the library.
4. **Sync & conflict:** reconnect, revision-checked push, two competing revisions, explicit recoverable choices, immutable revision history. The conservative default is to keep both versions.
5. **Share & agent:** copy private stable deep link, explain offline reachability, hand off ID + base revision + instructions to an authorized T3 agent, and review its candidate revision.

The walkthrough is a **visual prototype** only. Its local changes are simulated in browser memory and do not persist or contact OmniHub; the final editor must implement the behavior with durable storage.

## Shared engine proposal (requires approval before implementation)

- Use **one graph/document model and rendering/interaction core** for desktop and Android (same shape kinds, anchors, connectors, multi-selection, undo and revision serialization). Use adaptive chrome rather than separate canvas engines: phone bottom sheets/tool modes and touch gestures; desktop sidebar, palette and inspector, pointer and keyboard shortcuts.
- A TypeScript/SVG/canvas editor embedded inside Android via WebView is one plausible path to reuse, with Compose owning native navigation and OS share; confirm feasibility during the implementation task. Desktop can host the same editor in its web shell. Avoid duplicating geometry and undo in Kotlin.
- Treat the phone's offline document store and operation journal as durable **device-local** state independent of Hub availability. Mint the persistent diagram ID locally. A transport adapter synchronizes operations against a Hub-owned revision, with idempotent acknowledgements, replay after reconnect, and explicit conflict branches, never silent last-write-wins.
- Resolve collisions by surfacing both versions, making **save as copy** the non-destructive suggested choice; record any explicit overwrite with the original retrievable in history. Agent edits carry an exact base revision and return a proposed revision for acceptance by default.
- Example `omni://design/dgm_7c42` represents a **proposed** private deep link; offline copying does not imply that a different device or agent can open it before the document syncs. The production URI/router and authorization contract remain to be finalized during implementation.

## Evidence and feedback

TaskBoard `MISC-0161` is the authoritative visual-review surface. The HTML file is interactive; selected PNG screenshots in the TaskBoard review demonstrate exact Android/editor/library, offline, desktop inspector/library, conflict and agent states. Review direction first; do **not** start dependent implementation `MISC-0162` until the user has approved the native visual review.
