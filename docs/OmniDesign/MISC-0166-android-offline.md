# MISC-0166 — OmniDesign Android offline integration

## Entry and installation
- Android **Design** appears in the existing navigation overflow/customizable bottom bar. The dedicated screen embeds the **same** React/TypeScript canvas editor used on PC, bundled at Android build time (not a remote website). Android native Back waits for an IndexedDB write acknowledgement.
- `OmniSync.Android/app/build.gradle.kts` runs the shared editor Vite build and packages the result beneath `assets/omnidesign/`. The stable `https://appassets.androidplatform.net/assets/omnidesign/index.html` origin is provided by AndroidX `WebViewAssetLoader`, with JavaScript and DOM storage enabled. No file:// origin, network dependency or separate Kotlin diagram renderer.
- The existing phone-first canvas layout retains touch pan/pinch, selection, connectors, Add/Inspect sheets, Undo/Redo, templates and diagram browser. Header actions not needed mid-edit reside in the mobile **⋯** sheet; sync status remains visible.

## Offline storage and state
- **Local source of truth:** `OmniDesign` IndexedDB, schema v2, `diagrams` store containing the full native v1 structured document. The `sync` store contains the last *confirmed* PC snapshot/revision per ID; it is not derived from a document's nonzero revision.
- Every change on Android writes immediately (no desktop's 420 ms debounce). Back initiates one final IndexedDB write and waits for the editor acknowledgement, with a short fallback if WebView cannot respond.
- Documents created offline already have stable `dgm_<uuid>` IDs. The same ID is sent to the PC create endpoint; successful revisioned writes update the local document and baseline. Subsequent edits compare against the confirmed baseline, so pending state survives WebView/app restart.
- Library cards show **Local only / Pending / Synced / Conflict**; the persistent strip shows actual Hub reachability and pending count. A failed sync never removes local content.
- **Sync now** initially uploaded unsynced documents explicitly. Follow-up MISC-0167 added a durable IndexedDB outbox, automatic reconnect/resume sync while the editor is visible, paginated PC downloads, and recoverable conflict records. HTTP 409/412 never overwrite local edits; **Keep both** uses a stable fork ID before restoring the Hub original. See [MISC-0167](MISC-0167-sync.md).
- Hub library download does not overwrite a locally edited document with the same ID.

## Connectivity
- Only `api/health` and `api/v1/diagrams...` REST routes cross the native `@JavascriptInterface` transport. Native HTTP runs off the UI thread and returns asynchronous JSON to the editor. The bridge reads existing WebView cookies for a private Gateway endpoint; it adds no new authentication.
- **Hub sync address** in the Android screen is saved in local app preferences; default is `https://hub.rancorous.online/gateway/tools/omnidesign/` (the private remote route documented by MISC-0165). Configure a *reachable* private route if different. A LAN hostname on port 3333 is not yet guaranteed to reverse-proxy OmniDesign: the current local Gateway redirects to a loopback-only 8790 process. If login/reachability fails, the UI truthfully reports unreachable and keeps all edits local.
- Download/export from desktop browser is unchanged; Android-local source documents remain editable even when the PC or network is off. No unauthorised shared/live collaboration is introduced.

## Verification
- Shared editor production bundle builds. Android Kotlin compiles, and the Gradle asset merge includes `omnidesign/index.html` plus its JS/CSS.
- A real headless mobile browser run at 390×844 with the Hub API returning 503 created, stored, reopened, and displayed two local diagrams and the offline/pending badge. Evidence: `E:/_AgentScratch/Tasks/Misc/MISC-0166/Evidence/omnidesign_offline_mobile_{editor,library}.png`.
- An isolated **real OmniDesign FastAPI + SQLite** service on 127.0.0.1:8896 exercised offline create → sync revision 1 → offline edit/reopen → sync revision 2, retaining the exact same diagram ID.
- A physical APK/WebView session and production private Gateway sign-in were not exercised because no Android device appeared in ADB. Source/build success is **not** a claim of live phone deployment.
