# MISC-0167 — OmniDesign offline reconciliation

## What runs
The same `OmniDesign/web` bundle runs in a desktop browser or the Android
`WebViewAssetLoader` host. Android's native bridge only transports REST requests;
structured diagrams, confirmed PC snapshots, pending mutations and conflicts stay
in the device's IndexedDB (schema **v3**). No extra Android renderer or backend
service was introduced.

While the editor is visible on Android, it synchronizes when opened, on the
browser's online signal, on app resume, and at a 25-second connectivity poll.
**Sync now** is also available in the header on both clients. If the private
Gateway/authentication is unavailable, offline drawing and local saving continue
normally; this does not install background WorkManager synchronization while
the Design screen is closed.

## Durable protocol
- A document minted offline keeps its `dgm_<uuid>` ID and all element, port and
  connector IDs on PC upload. The PC is authoritative for `revision`; local
  autosave never increments it.
- Before network I/O an IndexedDB **outbox** entry records the entire native
  document payload, its base revision, operation method and a stable idempotency
  UUID. A retry replays that exact operation. The server's SQLite receipts make
  repeated PUTs idempotent; matching create POSTs are idempotent. If a create
  response was lost and the PC revision subsequently advanced, the client
  verifies immutable revision 1 to recognize its original write.
- Receipt processing is one IndexedDB transaction: confirmed baseline, local
  revision and outbox removal commit together. Edits made during the upload
  remain in the current local graph and are queued for the next revision.
- Reconciliation reads **every page** of Hub metadata, including agent-created
  documents. A clean replica downloads newer PC revisions. A dirty replica
  whose PC base has changed never uploads over it. HTTP 409/412 also creates a
  durable conflict record containing the remote snapshot.
- **Conflict → Keep both** writes the complete local graph under one stable
  fork ID *before* fetching the latest Hub version. It then atomically restores
  the remote at its original ID and clears the conflict. If the Hub is offline
  or an in-editor edit races resolution, the original conflict remains visible
  and the local fork stays recoverable; retrying reuses its ID.
- Status badges are based on confirmed baselines, durable outbox and conflict
  records: **Local only, Pending, Synced, Conflict**. PC JSON key ordering is
  normalized for comparison, preventing false dirty/conflict status.

## Verification
- The shared editor builds through `npm run build` (TypeScript/Vite).
- Isolated real FastAPI/SQLite + headless Chromium mobile-size integration,
  using an Android-native-bridge-shaped fixture: offline create → automatic
  reconnect → identical PC ID; agent edit while phone offline → durable
  conflict across reload → Keep both preserving original/fork; interrupted POST
  and PUT replies → restart/retry with no duplicate/revision bump; clean agent
  edit and new agent document → download. All passed.
- Script: `E:/_AgentScratch/Tasks/Misc/MISC-0167/Temporary/verify_sync.py`
  (temporary local verification only; not production or shipped app).
- **Not claimed:** deployment to a physical Android device, private Gateway
  sign-in from WebView, or background sync when the Design screen is closed.
  The APK must be rebuilt and installed to pick up the updated shared bundle.
