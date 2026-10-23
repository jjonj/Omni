# Android TaskBoard modernization — source milestone (2026-10-23)

Scope: `D:/SSDProjects/Omni/OmniSync.Android`. Assigned from user request to substantially update the outdated TaskBoard screen. No subagents; OMCP for PC work. Source edits limited to:
- `app/src/main/java/com/omni/sync/data/taskboard/TaskBoardStore.kt`
- `app/src/main/java/com/omni/sync/ui/screen/TaskBoardScreen.kt`

## Delivered in source
1. Replaced Wartribes-only mobile board assumption with local project discovery under the phone's `SharedWithPc/Obsidian/jjj/*/Tasks/_board.md`. The board picker displays each synced board's own configured `name`, remembers the selected root locally, and refreshes independently per board. Existing desktop OmniHub directory may display `Omni` using its frontmatter name; no directory rename or copy.
2. Reads all supported TaskBoard ID prefixes rather than filename-only `WT-` scans. Reads `task_prefix` and `epic_prefix` from each board; separate next-id allocation for tasks and epics, scanning Active+Archive. Existing WT/WTE mixed with newer prefixes remain addressable.
3. Reads current task schema: `kind=task|epic|for_user`, `depends_on`, `related_to`, `icon`, `completed_at`, and existing legacy fields. Archive IDs satisfy prerequisites without listing archived cards as active; unresolved links remain visible. Derived epic column + child completion count; avoids treating epics as normal executable tasks.
4. Phone-first **full-width grouped list**, with optional old horizontal Kanban mode. Search, task count, epic count, filters All/Attention/For user/Waiting/Epics, and a basic user queue entry point. Cards label epics and For-user actions, show waiting dependencies and related tasks.
5. Creation UI supports new tasks, epic containers, and human For-user actions. Editor exposes parent *epics* and within-board dependency/related IDs; preserves modern kind and link metadata, displays question-answer input and historical AI notes read-only. Saving an active For-user card into a terminal column changes it to an ordinary task and clears obsolete needs-user flag.
6. Revision checks remain in place for updates, with legacy parent edges preserved when unchanged. No unknown task ID prefixes silently disappear.

## Verification
- `gradlew :app:compileDebugKotlin --offline` PASS on modified source.
- `gradlew :app:assembleDebug --offline` PASS, output `app/build/outputs/apk/debug/app-debug.apk` (approx. 55.3 MB).
- No ADB-connected device at build time (`adb devices -l` empty). **APK was NOT installed on a phone; no runtime UI or synchronization claim.**
- Preexisting unrelated work in `MainActivity.kt`, `SignalRClient.kt`, `OmniAiModesScreen.kt` and many other repo files preserved; no Rust Hub/source changes, no service restart.

## Important remaining differences vs desktop Rust TaskBoard
- Android edits still use its legacy direct Markdown parser/writer. Further parity is needed for append-only description update timeline, history preservation, richer question/user queue skip+snooze semantics, canonical hard-dependency graph/cycles, position/epic proxy, For user claim/terminal behavior and cross-process locking.
- Phone SharedWithPc is a *synced copy*, not the PC's live B:/ drive; project folders must be synced to appear. The Android screen does not fetch TaskBoard via OmniHub's desktop CLI/OMCP transport.
- Current source deliverable is a **substantial first implementation**, not fully certified parity. For production task mutations, verify phone sync concurrency with a fixture first.

## Workboard
The pre-existing Omni board root remains `B:/GDrive/SharedWithPhone/Obsidian/jjj/OmniHub/Tasks`; naming shows Omni. Epic `OMNIE-0001` tracks the overhaul:
- `OMNI-0001` initial cross-project implementation + physical QA (In Progress).
- `OMNI-0002` remaining deep desktop workflow/transaction parity (AI Ready).
- `OMNI-0003` real-phone installation/synchronization/UI verification (AI Ready).

Source build log `D:/SSDProjects/Omni/OmniSync.Android/taskboard-modernize-assemble.log`.
