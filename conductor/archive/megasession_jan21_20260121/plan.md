# Implementation Plan: Megasession Jan 21 2026

## Phase 1: Windows Hub Improvements [checkpoint: 027d37d]
- [x] Task: TDD - Implement single instance check and focus existing window in `OmniSync.Hub` [verified]
- [x] Task: TDD - Implement "Quick Actions" startup routine system with once-per-day logic [verified]
- [x] Task: TDD - Add "Browser Tab Cleanup" as a startup quick action [verified]
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Windows Hub Improvements' (Protocol in workflow.md)

## Phase 2: Android UI/UX & Navigation
- [x] Task: TDD - Fix back button behavior to only dismiss keyboard when visible [verified]
- [x] Task: TDD - Adjust swipe sensitivity (distance + angle) in `Pager` components [verified]
- [x] Task: TDD - Remove microphone icon from folder view in Files screen [verified]
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Android UI/UX & Navigation' (Protocol in workflow.md)

## Phase 3: Android AI Screen & Alarms
- [x] Task: TDD - Implement diff-style rendering for `edit` and `replace` tool calls [verified]
- [x] Task: TDD - Fix auto-scroll logic for AI message list [verified]
- [x] Task: TDD - Ensure `clear` command reloads session history correctly [verified]
- [x] Task: TDD - Add custom "leave" text support for future-dated Alarm 2 [verified]
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Android AI Screen & Alarms' (Protocol in workflow.md)

## Phase 4: Android Browser & Web UI
- [x] Task: TDD - Implement title-based browser tab cleanup (case-insensitive) [verified]
- [x] Task: TDD - Add website selector dropdown to Omni Web UI [verified]
- [x] Task: TDD - Fix `Alt+A` hotkey consistency on TFT page [verified]
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Android Browser & Web UI' (Protocol in workflow.md)
