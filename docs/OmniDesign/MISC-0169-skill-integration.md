# MISC-0169 — Architecture diagram skill → OmniDesign

## Ownership and entry points

- **Agent skill (live):** C:/Users/crovea/.agents/skills/extra-skill-finder/on-request/architecture-diagram/SKILL.md. Shared skill finder routes architecture/flowchart/UML/ERD/network/OmniDesign-link requests to it.
- **Source of truth:** PC OmniDesign native v1 SQLite service launched on demand through the existing OmniHub Gateway, NOT the skill's historic hand-written SVG, a file attachment or a separate agent database.
- **API helper:** OmniDesign/service/agent_cli.py, invoked through OMCP on the PC. Local service defaults to 127.0.0.1:8790. Private permanent link is https://hub.rancorous.online/omnidesign/?diagram=dgm_<uuid>. A remotely unsynchronized ID is valid but does not resolve on the PC until sync.
- **Native contract:** omnidesign.document.v1.schema.json and service/server.py. Revisioned reads, 412 stale-write rejection and native atomic element patches were implemented in MISC-0168.

## Creating an editable diagram

The skill constructs a draft from a compact JSON layout and the **same stencil packs shipped with the editor**:

~~~powershell
cd D:\SSDProjects\Omni
py -B OmniDesign\service\agent_cli.py families
py -B OmniDesign\service\agent_cli.py draft --spec E:\_AgentScratch\Tasks\Misc\MISC-0169\Temporary\spec.json --output E:\_AgentScratch\Tasks\Misc\MISC-0169\Temporary\diagram.omd.json
py -B OmniDesign\service\agent_cli.py create --document E:\_AgentScratch\Tasks\Misc\MISC-0169\Temporary\diagram.omd.json
~~~

The draft step mints a stable document ID and fresh element/port IDs, preserves stencil identity and semantic defaults, validates the native schema and graph, and **explicitly reports local-only**. The create step checks the PC API reply and only then prints a saved status, revision 1 and stable private URL. Repeating the same draft POST while it remains revision 1 is idempotent; never mint a new ID for a retry. A disconnected Hub or unauthenticated HTML gateway response is non-success (no false remote-save assertion).

Built-in packs: architecture, flowchart, UML sequence/state, ERD, mind map, roadmap/timeline, network, game-system, floorplan, swimlane/process, wireframe (12). A template from an editor pack can initialize a native document; a node/link spec can author a tailored version. Stencils retain editable geometry, content, typed Inspector semantic data, stable ports and native attached connectors. Other shapes remain native even with mixed packs. Families are visual modeling aids, not semantic validators or a formal UML/DDL/CAD implementation.

### Catalog regeneration

The editor pack definitions under OmniDesign/web/src/packs.ts remain authoritative. An **exported JSON snapshot** at OmniDesign/service/agent_packs.json powers the offline Python drafting helper without requiring Vite or a browser at runtime. Rebuild the snapshot after changing editor built-in packs, from OmniDesign/web:

~~~powershell
node scripts/export_agent_catalog.cjs
~~~

The exporter bundles/reads the actual editor packs using its installed esbuild dependency. Do not edit the snapshot independently. This is a catalog only, not another document store or renderer.

## Editing an existing linked native diagram

~~~powershell
py -B OmniDesign\service\agent_cli.py read 'dgm_<uuid>' --output E:\_AgentScratch\Tasks\Misc\MISC-0169\Temporary\existing.omd.json
py -B OmniDesign\service\agent_cli.py patch 'dgm_<uuid>' --revision <read-revision> --operations E:\_AgentScratch\Tasks\Misc\MISC-0169\Temporary\operations.json
~~~

Always read the live native document/revision first. For direct writes explicitly authorized by the user, patch complete native elements with add/replace/remove or rename operations. Leave unaffected groups, layers, paths, ports, libraries and unknown extension fields untouched. Existing connector references must stay valid. Revision CAS and durable idempotency receipts prevent lost concurrent edits and retry duplicates. Otherwise use the existing revision-pinned propose command for an approval-ready full document. A proposal is not an active edit until accepted.

- **HTTP 404:** ID unknown or still offline-only. **410:** archived. **412:** stale revision, no mutation; re-read/rebase. **422:** invalid structure/dangling graph. **428:** missing write precondition. **Transport failure:** Hub unavailable. No client claims a remote save for any of these.
- Offline creation: retain the native .omd.json file as a **local-only draft**, outside expiring scratch if it needs to persist, and upload using the same ID later. Offline modification of a cached existing revision is at most a local proposal until live revision reconciliation.
- **Derived exports:** The shared editor renders SVG/PNG from the native v1 graph. They are optional presentation/review artifacts, not the editable master; a historical static SVG/HTML example is only style reference.

## Focused verification (2026-11-19)

On an isolated FastAPI/SQLite HTTP server using OMNIDESIGN_DATA_DIR under the task's E: Temporary directory (never the live production database):

1. Drafted and schema/graph-validated all 12 editor template families.
2. Created a native flowchart through agent_cli.py and verified receipt's stable dgm_ ID, link and revision 1.
3. Retried the exact create and verified idempotence; resolved the link to the same native document.
4. Replaced one shape via revision-pinned native PATCH; same document ID, unrelated elements retained, revision advanced to 2.
5. Submitted a competing stale patch and observed HTTP 412/no write; attempted read on a disconnected port and observed nonzero exit with an unreachable error.

The isolated server was stopped. No user production diagrams were created. Browser-rendered export appearance, remote owner-auth proxy and actual Android-device navigation were **not** exercised in this integration check.
