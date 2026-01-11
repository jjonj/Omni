# Plan: Project Environment Launcher

This plan outlines the implementation of the "Project Environment Launcher" feature for OmniHub, enabling automated workspace preparation.

## Phase 1: Prototyping & Research
- [x] Task: Research Windows Explorer Tab Logic (Shell COM/UI Automation)
- [x] Task: Research OneCommander Tab Logic (CLI/API)
- [x] Task: Implement Prototype script for Explorer/OneCommander tab opening
- [x] Task: Conductor - User Manual Verification 'Phase 1: Prototyping & Research' (Protocol in workflow.md)

## Phase 2: Core Logic & Data Models
- [ ] Task: Define `Project` and `Action` (OpenFolder, RunProgram) data models in OmniSync.Hub
- [ ] Task: Implement `ScreenRatio` to Pixel conversion logic (with multi-monitor support)
- [ ] Task: Implement `WindowDetector` logic (Check if folder/program is already active)
- [ ] Task: Implement `ProjectLauncher` service to execute actions sequentially
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Core Logic & Data Models' (Protocol in workflow.md)

## Phase 3: Hub UI & Settings Integration
- [ ] Task: Update OmniSync.Hub Settings UI to allow CRUD for Projects
- [ ] Task: Add "Actions" editor (Folder Path, Program Path, Layout selection)
- [ ] Task: Integrate Projects with existing Hotkey Manager
- [ ] Task: Ensure Web Settings UI is synced with Hub (Read/Write Project config)
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Hub UI & Settings Integration' (Protocol in workflow.md)

## Phase 4: Layout Capture Tool
- [ ] Task: Implement "Layout Capture" helper (Iterate open windows, extract bounds, convert to Ratios)
- [ ] Task: Add "Capture Current" button to the Project settings UI
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Layout Capture Tool' (Protocol in workflow.md)

## Phase 5: Finalization & Polish
- [ ] Task: Implement successive launch handling (queuing/debouncing hotkey triggers)
- [ ] Task: Final end-to-end integration testing with multiple projects
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Finalization & Polish' (Protocol in workflow.md)
