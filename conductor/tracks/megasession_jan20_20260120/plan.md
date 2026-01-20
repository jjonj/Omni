# Implementation Plan: Megasession Jan 20 2026

## Phase 1: Windows Hub Improvements
- [ ] Task: TDD - Implement single instance check and focus existing window in `OmniSync.Hub`
- [ ] Task: TDD - Implement "Quick Actions" startup routine system with once-per-day logic
- [ ] Task: TDD - Add "Browser Tab Cleanup" as a startup quick action
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Windows Hub Improvements' (Protocol in workflow.md)

## Phase 2: Android UI/UX & General Fixes
- [ ] Task: TDD - Fix back button behavior to only dismiss keyboard when visible
- [ ] Task: TDD - Disable auto-scroll for dashboard activity logs
- [ ] Task: TDD - Fix general auto-scroll logic (only disable on scroll up)
- [ ] Task: TDD - Remove microphone icon from folder view in Files screen
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Android UI/UX & General Fixes' (Protocol in workflow.md)

## Phase 3: Android Features & Alarms
- [ ] Task: TDD - Pause/Resume sleep tracker based on Hub connection status
- [ ] Task: TDD - Implement title-based browser tab cleanup (case-insensitive)
- [ ] Task: TDD - Add daily repeat toggle to Alarm #1
- [ ] Task: TDD - Add specific future date support for Alarm #2
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Android Features & Alarms' (Protocol in workflow.md)

## Phase 4: AI Screen Enhancements
- [ ] Task: TDD - Fix message identity for CLI-originated messages to show as "Me"
- [ ] Task: TDD - Implement diff-style rendering for `edit` and `replace` tool calls
- [ ] Task: Conductor - User Manual Verification 'Phase 4: AI Screen Enhancements' (Protocol in workflow.md)

## Phase 5: Web & Chrome Extension
- [ ] Task: TDD - Add website selector dropdown to Omni Web UI
- [ ] Task: TDD - Fix `Ctrl+A` hotkey consistency on TFT page
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Web & Chrome Extension' (Protocol in workflow.md)
