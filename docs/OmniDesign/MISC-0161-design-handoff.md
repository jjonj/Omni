# MISC-0160 / MISC-0161 — OmniDesign UI/UX design handoff

**Current state:** Revision 3 was explicitly **approved by the user** on both MISC-0160 and MISC-0161 (TaskBoard round 3, choice A, 2026-11-19). The approved mockups are ready to hand off to MISC-0162. No Android, production desktop editor, OmniHub service or sync implementation has begun.

## Source

`D:/SSDProjects/Omni/docs/OmniDesign/OmniDesign-r3-prototype.html` is a single self-contained, responsive prototype. It switches to the Android layout at widths ≤ 760 px and the desktop layout above that. It supersedes `MISC-0161-interactions.html` (R2), which stays only for history.

Deep links for review: append `#s=<scenario>`, where the scenario is one of `lib`, `new`, `ed:<id>`, `sel`, `multi`, `shapes`, `edge`, `add`, `offline`, `conflict`, `agent`, `diff`, `share`, `handoff`, `history`, `sync`, `export`. Phone-only scenarios are `sheet`, `esheet`, `zoom` and `outline`.

## What R3 changes

- **The canvas is the architecture-diagram skill output.** It uses the same 1200×700 page, colours (`#14161a` / `#1d2026` / `#e6e2d8` / `#a09a8c` / `#e0a458`), Segoe UI, solid ivory vs dashed amber boxes, orthogonal labelled arrows, chips and item lists. The app chrome around it is quiet so the diagram stays the focus. Export → HTML figure produces the same page format as the skill.
- **Kits:** architecture, flowchart, sequence, state machine, data model, network, roadmap, floor plan, swimlane and mind map. All are drawn in the same house style, and there are 10 example diagrams.
- **Desktop:** library grid with real previews and a "waiting for you" agent strip. The editor has a left tool rail, a contextual inspector, a shapes flyout, edge editing, multi-select align, history, export and a share/hand-to-agent dialog.
- **Android:** a phone-first editor with a bottom tool bar (Select / Connect / Add / Undo / Redo), a Canvas ⇄ Outline switch, and bottom sheets for editing shapes and connections. It has 44 px+ touch targets, full-width sheets and a contextual quick-tool bar when zoomed in.
- **Offline and conflict:** an offline banner with a pending-change count. The conflict sheet shows the choices first (Keep both is recommended), with visual previews below.
- **Agents:** proposals show as a Current/Proposed diff that you accept or discard. Handoff defaults to "Propose a revision".

All behaviour is simulated in browser memory. It is not real persistence, sync, sharing or agent writes. The production direction for MISC-0162 is unchanged: **one shared graph/document/interaction engine** embedded in Android and desktop, with platform-adapted chrome, a device-local journal and a revision-checked OmniHub adapter.

## Acceptance

Native TaskBoard review verdicts: **MISC-0160 approved at 03:26:31Z**, **MISC-0161 approved at 03:26:34Z**, both round 3 / A. The complete interactive HTML is stored on both native review cards; source is the repository file above. Screenshot evidence is stored in each review's attached artifacts (working captures: `E:/_AgentScratch/Tasks/Misc/MISC-0160/Evidence/r3/`). Approval is a visual design gate, **not** evidence of production functionality.
