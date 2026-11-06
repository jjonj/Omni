# Android TaskBoard parity audit — 2026-11-06

Reference: current desktop TaskBoard in `D:/SSDProjects/Tools/TaskBoard`.
Phone: `OmniSync.Android/app/src/main/java/com/omni/sync/{ui/screen/TaskBoardScreen.kt,data/taskboard/}`.
PC authority: Rust OmniHub `src-omnihub-tauri/src/taskboard_phone.rs` and TaskBoard's Rust task-core.

## Verified source coverage

- Already present before this audit: multi-board discovery and editing; epics, dependencies and columns; search/sort/filter and compact/Kanban layouts; user-attention task queue and snooze; visual review screen; offline durable edits with PC CAS/conflict receipts; task queue modes and column auto-queue policy; generic Android T3 session interface.
- **Implemented now — performance:** no full vault markdown reread every seven seconds; 15s metadata probes and bounded three-minute full reads; fast indexed task/dependency/epic lookups; slower PC queue reads (45s rather than 9s). Memory-only indexes, no unbounded disk cache.
- **Implemented now — queue:** task Queue/Backlog and column-wide auto-queue for any AI-ready, non-advisory and nonterminal column; PC uses TaskBoard's authoritative readiness check. Column toggle aligns existing eligible cards with authoritative PC revisions, not only future default. Read-only Android queue overview shows PC-owned queued/starting/working state and links to cards.
- **Implemented now — T3:** task editor offers explicit task-bound Spawn T3/Open T3. The authenticated PC bridge resolves the allowlisted board and uses the existing T3 open-task serialization/idempotency mechanism, rather than spawning generic unbound sessions. Confirmed IDs navigate to the Android AI session screen. Uncertain outcomes warn against blind retries.

## Parity gaps still present (not claimed implemented)

- **Desktop unified Inbox** review/For user quick-answer experience with structured choice buttons, Done/keep-implementation, last-answer undo, and automatic queue resume after opening the full editor. Android has separate visual review and user-attention task queue; it does **not** mirror this complete new unified interaction.
- **Desktop Release Center** and release/deployable management UI are not present in Android TaskBoard.
- **Desktop exact queue/capacity management** (OpenRig seat telemetry, reservations/recovery and manual Start-now controls) is not fully present. The phone overview only displays PC TaskBoard queue intent/state.
- **Richer links** (clickable attachments, T3 session links, HTML presentation links) in the new desktop Inbox are not yet equivalent in the phone task editor.
- The phone's explicit Spawn T3 flow currently defaults to ChatGPT; native Claude/Codex task-bound launches need deliberate provider/environment selection.
- The Android app uses its own layout; not all desktop drag/drop, review and keyboard interactions translate to touch; this is not a visual identity test.

## Build and activation

- Android debug APK: `gradlew :app:assembleDebug --offline` **PASS**.
- Rust OmniHub: `cargo check --manifest-path src-omnihub-tauri/Cargo.toml --offline` **PASS** (existing unrelated warnings).
- No phone connected to ADB at build time; **physical install and phone interactions unverified**.
- New PC RPC/T3 bridge is **source-only** until a validated Tauri production OmniHub release is staged and activated. Do not deploy plain Cargo release: the Hub needs frontend-embedded Tauri build and a SHA-pinned release manifest. Other agents are concurrently editing Hub sources; do not restart its live service as part of this phone audit.
- Queue endpoints used by prior Android builds remain present; new features requiring the new PC Hub RPC must wait for the matching Hub release.

This is a bounded parity update, **not full desktop feature parity**.
