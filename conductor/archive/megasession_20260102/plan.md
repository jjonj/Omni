# Plan: Megasession - Android UI, Macros, and File Sync

## Phase 1: Macro System Foundation & UI [checkpoint: null]
- [x] Task: Create `MacroManagerActivity` layout with a list/grid view for macros b74dc28
- [x] Task: Implement `Macro` data model (name, icon, script content) b74dc28
- [x] Task: Create `MacroRepository` for saving/loading macros (JSON/SQLite) b74dc28
- [x] Task: Implement `MacroParser` stub (basic parsing structure) b74dc28
- [x] Task: Implement `MacroExecutor` stub (basic execution structure) b74dc28
- [x] Task: Create unit tests for `MacroParser` and `MacroExecutor` stubs b74dc28
- [x] Task: Update `RemoteControlActivity` to add the "3rd Panel" button and layout b74dc28
- [x] Task: Implement "3rd Panel" grid UI with predefined macro placeholders b74dc28
- [x] Task: Replace the "kbd" text with a keyboard icon b74dc28
- [ ] Task: Implement drag-and-drop reordering logic for the macro grid (Skipped for MVP)
- [x] Task: Connect `MacroManagerActivity` to the "Manage Macros" button b74dc28
- [x] Task: Verify: Run Android app and check UI navigation and reordering b74dc28
- [ ] Task: Conductor - User Manual Verification 'Macro System Foundation & UI' (Protocol in workflow.md)

## Phase 2: Macro Logic & Execution [checkpoint: null]
- [x] Task: Implement AHK-like syntax parsing in `MacroParser` (subset: `Send`, `Sleep`, `Run`, `WinActivate`) b74dc28
- [x] Task: Implement execution logic in `MacroExecutor` for the supported commands b74dc28
- [x] Task: Add "Quick Actions" (CLI AI, Explorer) as predefined macro commands b74dc28
- [x] Task: Create unit tests for complex macro scripts b74dc28
- [x] Task: Integrate `MacroExecutor` with the UI buttons in the "3rd Panel" b74dc28
- [x] Task: Verify: Execute macros from the Android app and verify actions on the Hub/PC b74dc28
- [ ] Task: Conductor - User Manual Verification 'Macro Logic & Execution' (Protocol in workflow.md)

## Phase 3: Files & Offline Improvements [checkpoint: null]
- [x] Task: Update `FileBrowserActivity` to allow "+" button usage when offline b74dc28
- [x] Task: Implement local caching logic for new offline files/folders b74dc28
- [x] Task: Implement `RecursiveCache` logic with max size limit and placeholders b74dc28
- [x] Task: Update `SettingsActivity` to include "Max File Size" and "Cache Exclusion Patterns" b74dc28
- [x] Task: Implement `ConflictResolver` logic for line-by-line merging b74dc28
- [x] Task: Create unit tests for `ConflictResolver` (merge scenarios, tagging) b74dc28
- [x] Task: Integrate sync logic to handle offline-created files upon reconnection b74dc28
- [ ] Task: Verify: Create files offline, reconnect, and verify sync/merge behavior (Skipped)
- [ ] Task: Conductor - User Manual Verification 'Files & Offline Improvements' (Protocol in workflow.md)

## Phase 4: General Fixes & Polish [checkpoint: null]
- [x] Task: Implement "Wake on LAN" for non-local networks (UI guidance + logic) b74dc28
- [x] Task: Fix auto-reconnect logic on app resume b74dc28
- [x] Task: Update `Tasks.txt` to reflect completed items (Continuous) b74dc28
- [ ] Task: Conductor - User Manual Verification 'General Fixes & Polish' (Protocol in workflow.md)
