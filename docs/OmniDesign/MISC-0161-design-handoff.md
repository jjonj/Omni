# MISC-0161 — OmniDesign Android / offline interaction review

**Current state:** Revision 2 mockups prepared for user approval. No Android, desktop-editor, OmniHub service, or synchronization implementation has begun. The dependent implementation task MISC-0162 must wait for an explicit approved verdict on **MISC-0161's native TaskBoard review**.

## What changed after the rejected review

The user rejected R1 because its generic blue/white canvas did not match the architecture-diagram skill. R2 now uses the actual reference `C:/Users/crovea/.agents/skills/extra-skill-finder/on-request/architecture-diagram/{SKILL.md,example.html}` for visual identity: charcoal `#14161a` paper, dark `#1d2026` shape cards, warm ivory `#e6e2d8` text and strokes, muted `#a09a8c` explanations, restrained dashed amber `#e0a458` only for optional paths. Connectors are orthogonal and explicitly labelled. Example canvas shows **Game client → Match server → Recording**, with an optional backend branch.

## Interactive walkthroughs

The single self-contained HTML source is `D:/SSDProjects/Omni/docs/OmniDesign/MISC-0161-interactions.html` and the stable OmniHub presentation ID is `misc-0161-omnidesign-interactions`.

- **Library:** Android/PC browse and manage diagrams with actual dark mini-diagram previews; open, rename, duplicate, archive and choose a diagram vocabulary.
- **Draw/edit:** select and drag the same SVG nodes by touch/mouse, place shapes, connect, pinch/wheel zoom and pan. Properties use a phone bottom sheet and desktop inspector.
- **Offline:** Hub unreachable indicator, new locally minted stable ID, editable offline canvas and explicit queued local changes.
- **Reconnect:** revision-checked sync, conflict between local and Hub edits with two *visual* revision previews, non-destructive save-copy default, timeline/history.
- **Share/agent:** persistent `omni://design/<id>` proposal, copy, pending-offline reachability caveat, base-revision handoff, candidate revision review.

All state is simulated inside the visual prototype. Browser-memory operations are not actual device persistence, network sync, sharing or T3 agent writes. The production architecture proposed for MISC-0162 is **one shared graph/document/interaction engine** embedded in Android and desktop, with adaptive platform chrome, device-local document journal and revision-checked OmniHub adapter. Do not treat mockup behavior as production evidence.

## Acceptance and review

The R2 TaskBoard review must be decided by the user. The editable HTML and screenshots for Android library/editor/properties/offline/agent and PC library/editor/conflict are the evidence. Do not self-approve. Once the user accepts the visual direction, MISC-0162 can implement the actual shared editor, durable local storage, reconnection and revision workflow.
