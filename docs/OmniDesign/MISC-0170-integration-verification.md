# MISC-0170 — OmniDesign integration verification (2026-11-19)

## Scope and verdict

**Result: integrated storage, agent, shared-editor, and offline-sync round trip PASS in the reproducible browser/WebView-bridge harness; native Android APK smoke PASS for link/load and connectivity transitions; native handset visual and end-to-end touch-edit acceptance NOT YET PROVEN.** Keep the visual verdict separate from the functional verdict.

The test runs the *actual* OmniDesign FastAPI service, SQLite persistence, Python agent CLI, shipped React editor, and IndexedDB/synchronization implementation. Its Android leg uses mobile Chromium at 390×844 with real touch input plus a deliberately WebView-shaped transport shim implementing the Kotlin `OmniDesignNative.request` interface and configurable loss; it does **not** run Kotlin code in that leg. The native Android APK is checked separately below. No mock or screenshot is represented as a real remote save.

### Reproduction

From `D:\SSDProjects\Omni` on a PC with Python, FastAPI/uvicorn, Playwright and Chrome:

```powershell
$env:OMNIDESIGN_QA_DIR = 'E:\_AgentScratch\Tasks\Misc\MISC-0170'
py -B OmniDesign\qa\verify_roundtrip.py
```

The script launches a service against a **unique temporary SQLite data directory** on loopback port 8898, drives the live editor, and terminates the test service. It never writes production diagram records, never restarts the existing Hub service, and emits `Evidence/roundtrip-result.json` and UI/export evidence under the chosen QA directory. Do not mistake isolated-server links in the JSON receipt for publicly dereferenceable links after that test service stops.

### Observed functional evidence

| Step | Actual observation |
| --- | --- |
| Agent creates native graph | `agent_cli draft/create` created an architecture graph with **3 nodes + 2 live edges**, revision **1**, stable `dgm_...` ID and permalink. |
| PC opens permalink | Desktop Chromium loaded the **exact ID**, rendered 5 graph elements, and remained editable. |
| Offline mobile edit | 390×844 touch-driven shared editor selected a real node using the mobile Outline/Inspector and changed its text while transport was offline. IndexedDB retained 3 nodes + 2 edges, original ID, and pending status. |
| Automatic reconnection | Restoring the native-bridge fixture transport triggered synchronization **without clicking Sync now**; PC received revision **2** with the phone edit. |
| Agent edits SAME permalink | `agent_cli read` resolved revision 2 and `patch` replaced an existing node using conditional revision. Revision **3**, identity and both connectors preserved. PC and mobile editor each displayed the final node text. |
| Concurrent edits | Mobile offline change + agent PC change produced a stale **HTTP 412**, persisted visible conflict through mobile reload, and `Conflict · Keep both` forked the phone graph without replacing PC revision 4. |
| Dropped response/retry | Simulated a lost POST response **after** PC commit, reloaded the mobile editor, and observed one and only one diagram ID, revision **1** after durable retry. The test found an intermittent false-conflict race at linked-diagram reload (see fix below). After the fix, two consecutive complete walkthroughs passed the same dropped-receipt/restart case. |
| Restart persistence | Killed and relaunched the **test service process**, preserving its SQLite directory. Offline CLI read refused to claim a save; same permalink then returned original ID and revision **4** with agent changes after restart. |
| Multiple diagram families | Same native API persisted architecture (5 elements), game-system (11), and ERD (5); original links/connectors remain structured objects. These are 3 families sampled, not exhaustive approval of the catalog. |
| Export | The actual editor downloaded `.omd.json` (8,585 bytes), SVG (3,648 bytes) and PNG (122,804 bytes); native JSON kept original ID, revision and 5 editable objects. |

### Integration defect corrected during MISC-0170

When the server committed a mobile POST but the response disappeared, reopening that link could race the outbox replay. `App.openFromHub` saw a remote revision without any confirmed baseline and immediately recorded a conflict, even though a **durable pending POST with the exact original payload** was available to determine whether the remote first revision matched. In `OmniDesign/web/src/App.tsx`, linked-diagram refresh now detects that pending create and **retains its outbox and local graph instead of prematurely fabricating a conflict**. The existing synchronizer still replays the same POST; a genuinely different remote payload remains a 409 conflict. The full dropped-receipt + restart acceptance path then passed twice consecutively. This is a behavioral fix, not a workaround in the harness.

**Machine-readable receipt:** `E:\_AgentScratch\Tasks\Misc\MISC-0170\Evidence\roundtrip-result.json` (12 checks PASSED in final isolated run). Screens: `pc-agent-open.png`, `pc-final-native.png`, `phone-offline-inspector.png`, `phone-after-sync.png`, `phone-final-native.png`, `phone-conflict.png`. Derived exports: `export.json`, `export.svg`, `export.png`.

### Real PC service and Android APK smoke

- Connected **production PC service** at `127.0.0.1:8790`, health `/api/health` reported `service=omnidesign,status=ok`. Created a clearly titled persistent QA architecture graph (native revision **1**) through the normal CLI. Its *private*, owner-authenticated link is `https://hub.rancorous.online/omnidesign/?diagram=dgm_3f3faed5-c6da-4cb4-b589-3ceefbce545c`. Re-read via CLI after save: `Evidence/pc-persistent-qa-native.omd.json`. This link is an **actual PC-stored document**, distinct from the isolated QA links. Remote authenticated internet access was **not** independently verified.
- No physical handset was available over ADB. Created a task-scoped Android **x86_64 API 36.1 emulator** from the installed system image (original registered AVD had a broken/missing arm-config). Installed the prebuilt Omni Android debug APK, opened the *same production QA ID* via `omnidesign://diagram/...`, and configured its real Kotlin native API bridge to `http://10.0.2.2:8790/` (emulator's private host alias).
- **Native APK accessibility tree** confirmed the QA title, three graph labels, both edge labels, `Hub connected`, `Synced · 0 unsynced`, and actual editor controls. Turning airplane mode on changed state to `Hub unreachable · offline`; turning it off and using Sync now brought state back to `Hub connected`. Evidence `Evidence/android-emulator-online-a11y.xml` and emulator screenshots. Thus the installed native host, URL deep link, shared document rendering **in the accessibility tree**, and Kotlin/Hub transport were exercised, not merely read from source.
- **Not demonstrated on real Android:** native device shape editing while disconnected, reliable visual drawing, real-device pinch/drag, and their subsequent full native-phone save-and-agent-edit round trip. On this headless SwiftShader emulator, Android `screencap` captured Compose header and fixed bottom nav but **blank WebView editor chrome/canvas** despite the UIAutomator tree showing elements; topbar hit testing was unreliable. Treat as an emulator renderer/capture **or product issue requiring a visible emulator / physical handset**, not as proof that real handset visuals pass. `emulator-real-screen.png` records the observed black frame; do not silently treat the screenshot as a passed match.
- Android native application storage and Hub API were not modified; the app was installed only on task-scoped emulator data. No physical device or real remote-airline network was used.

### Visual comparison to approved R3 mockups (not newly approved)

MISC-0160's R3 desktop/phone and MISC-0161's R3 integration mockups were recorded as approved **design references**; implementation screenshots are **not** a user visual-approval event. Compared `E:\_AgentScratch\Tasks\Misc\MISC-0160\Evidence\r3\{d-ed.png,p-ed.png,p-zoom.png}` against this run's `pc-final-native.png`, `phone-final-native.png`, `phone-offline-inspector.png`:

1. **MISC-0166 Android host/mobile UX:** approved phone shows an immediately discoverable **Canvas / Outline segmented switch**; the shared editor currently buries Outline under `⋯ → Canvas / Outline` because the top action is hidden by responsive CSS. At auto-fit on 390×844, 5 native elements were rendered at **25%** and text was largely illegible without zoom; approved phone view uses more legible page presentation. Screens also lack the approved native-looking structured header/status treatment. Needs owning-task human visual review and physical tap/pinch confirmation.
2. **MISC-0163 shared PC editor:** approved desktop mockup shows a visually framed page with title/subtitle/caption and dedicated agent-review affordances; current editor is a generic canvas/inspector with title in the toolbar and without the mockup's page narrative or prominent agent diff/handoff controls. Data operations work, but no fidelity assertion to R3 is justified.
3. **MISC-0164 diagram libraries:** architectural symbols, game-system and ERD native semantics survived create/export. No acceptance evidence here for all other families, advanced custom library interactions, or mobile stencil search ergonomics.
4. **MISC-0166 Android emulator:** blank captured WebView frame in headless AVD is a **visual verification blocker**, even though accessibility and connectivity work; request a visible GPU emulator or physical handset check. Do not reinterpret the mockup's recorded approval as approval of this build.

**Decision:** functional integration is ready for reviewer inspection, with this explicit scope limit. The all-native Android and final visual acceptance clauses remain **open until actual interactive handset/visible emulator evidence**; no silent visual approval is implied.

### Evidence provenance and constraints

Latest isolated test: `roundtrip_db_b12cd6f35db14b4699271c8bf1bf3e3a` (separate database; 12/12 PASS). Source under test: working tree on 2026-11-19; this task adds a narrowly scoped missed-create-acknowledgement recovery fix, a regression harness and this QA report, leaving other owning implementations to their agents. Browser transport shim intentionally exercises HTTP status, delayed/missing receipts and IndexedDB state; it does not certify Android's Kotlin `HttpURLConnection` transport under hostile networks. Device-level failure has no definitive root-cause verdict.

### Follow-up acceptance gate

On a real Android device / visible hardware-rendered emulator: open the production QA link, airplane-mode offline, change a native **node** and make another local diagram, force-close/relaunch, reconnect, inspect the original IDs and PC revisions, have the CLI patch the same link, verify final PC+handset drawing, pinch/drag and exports; compare visually to approved R3 and record a fresh human verdict on MISC-0163/0166. The test harness provides deterministic coverage of the underlying data path in the meantime.
