# Plan: Megasession Jan 10

## Phase 1: Hub Backend Improvements [checkpoint: 9c2363c]
- [x] Task: Implement 'Reload All Sessions' logic in Hub.
    - [x] Sub-task: Create logic to generate `OMNI_SESSION_DEBUG_REPORT.LOG` in root.
    - [x] Sub-task: Implement session comparison (Android reported vs. Hub actual).
    - [x] Sub-task: Implement "Force Reload" command for SignalR clients.
- [x] Task: Implement Window Focus logic in Hub.
    - [x] Sub-task: Use Win32 `SetForegroundWindow` or similar on the session's tracked PID.
- [x] Task: Implement Preset Storage in Hub.
    - [x] Sub-task: Add JSON-based storage for user presets (linked to user/device if applicable).
    - [x] Sub-task: Expose SignalR methods to Fetch, Add, and Delete presets.
- [ ] Task: Conductor - User Manual Verification 'Hub Backend Improvements' (Protocol in workflow.md)

## Phase 2: Android AI Screen Enhancements
- [ ] Task: Fix Chat Echo/Ghosting issue.
    - [ ] Sub-task: Identify why Hub echoes user messages back as AI messages and add filter.
- [ ] Task: Update AI Screen UI for Session Management.
    - [ ] Sub-task: Replace "Reset" button with "Reload" and wire to Hub's new logic.
    - [ ] Sub-task: Update Browse dropdown to allow choice between `dir add` and `new session`.
- [ ] Task: Implement Focus Button in AI Screen.
- [ ] Task: Implement Preset Message UI.
    - [ ] Sub-task: Create dropdown for default and custom presets.
    - [ ] Sub-task: Implement long-press save logic.
- [ ] Task: Implement Chat Auto-scroll.
    - [ ] Sub-task: Logic to detect if scroll is at bottom before auto-scrolling on new message.
- [ ] Task: Conductor - User Manual Verification 'Android AI Screen Enhancements' (Protocol in workflow.md)

## Phase 3: Android Files Screen & General Fixes
- [ ] Task: Implement State Persistence for Files Screen.
    - [ ] Sub-task: Save scroll/cursor position when switching screens.
    - [ ] Sub-task: Prevent auto-closing of files on screen navigation.
- [ ] Task: Fix File Link Navigation.
    - [ ] Sub-task: Update click handler to specifically search for file paths in the workspace.
- [ ] Task: Final Polish & Request.
    - [ ] Sub-task: Verify all tasks are complete.
    - [ ] Sub-task: Call `aispeak` for CLI repo access request.
- [ ] Task: Conductor - User Manual Verification 'Android Files Screen & General Fixes' (Protocol in workflow.md)
