# Android TaskBoard parity and performance audit — 2026-11-06

Scope: Android source under `OmniSync.Android`, desktop TaskBoard under `D:/SSDProjects/Tools/TaskBoard`, and the Rust OmniHub `taskboard_phone.rs`. Source review and Kotlin compile only; no device connected for end-to-end verification.

## Present in current Android working tree
- Task filtering, sort, hidden/dependent task filtering, priority, epics, column management and snoozing.
- Authenticated phone-to-PC TaskBoard-core edits with persisted operation IDs, offline replay, and conflict handling (`TaskBoardPhoneClient`).
- PC-owned Queue/Backlog via `taskboard.phone.queue.list/set`, per-column `auto_queue` and queue-all alignment, queue badges and bulk queue toggles.
- Task-bound T3 session open/spawn through `taskboard.phone.session.open` (no generic session creation).
- Reduced phone background load: metadata-only task file signatures at 15-second intervals, periodic full reload, queue poll increased to 45 seconds; per-snapshot task and epic indexes. These changes are **uncommitted concurrent work** at audit time; do not mistake them for a deployed APK.
- Native visual-review artifact selection, images, annotations and submission.

## Parity gaps and risks
1. **P1 — New-task execution choice missing.** Desktop new-task flow explicitly offers Backlog / Queue / Start now, and forbids Backlog in auto-queue columns. Android new-task form sends only a TaskBoard draft and provides none of those three choices. Existing-task T3 Spawn is not a substitute for the creation workflow. A PC create receipt contains a task ID, but the phone `Submission` drops it, preventing the follow-on Queue/Start-now action before filesystem sync. The user should not be told new-task mode parity is complete.
2. **P1 — Review Inbox is not the desktop feedback workflow.** Android `TaskBoardReviewScreen` handles synced visual review manifests; it does not expose desktop Review Inbox actions such as Needs changes / Done / keep implementation, undo previous answer, priority before response, or the T3 review lifecycle. Implement against the authoritative PC/TaskBoard API, not by guessing from visual review manifest state.
3. **P1 — No device or Hub-runtime acceptance proof.** Kotlin `compileDebugKotlin` passed on 2026-11-06, but ADB listed no connected device. APK on disk predates these working changes. The Hub server route exists in source; runtime deployment/version parity and remote task spawn/queue CAS still need device verification.
4. **P2 — Expensive queue enumeration on PC.** `taskboard.phone.queue.list` performs one board load and then `execution_intent` once for each eligible task (reads/parses each task file). Every active phone now does this about every 45s. Avoid per-item filesystem lookups or implement change-based responses if large-board latency is still measurable. Do not cache stale queue states across writes.
5. **P2 — Queue mode guard.** Android card/bulk controls can request Backlog for tasks in an auto-queue column; compare with desktop's creation-only restriction and ensure UI language/policy does not imply all existing tasks must remain queued forever. The column Queue-all action currently performs multiple separate CAS operations and may stop after partial success; report partial completion and allow resync.
6. **P2 — Phone Board snapshot is sync-derived.** Task and column visibility still depends on `SharedWithPc` filesystem propagation; PC acknowledgement of a write does not mean immediate phone snapshot visibility. Avoid treating background refresh as authoritative and give suitable UX.

## Validation / coordination
- `gradlew.bat :app:compileDebugKotlin --offline --console=plain`: PASS, 14 Gradle tasks.
- No tests, device install or Hub restart performed.
- Concurrent uncommitted changes were observed in Android `TaskBoardPhoneClient.kt`, `TaskBoardStore.kt`, `TaskBoardScreen.kt`, and Rust Hub `taskboard_phone.rs`. Avoid staging or overwriting another agent's work. Complete those changes first, then package/deploy both Android and Hub as needed.
