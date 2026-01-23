# Implementation Plan - Megasession Jan 23 2026

## Phase 1: OmniSync.Hub - History Limit
- [x] Task: Implement history limit logic in `HubEventSender` or relevant service.
- [x] Task: Add "Max History Characters (k)" setting to the Hub WPF UI.
- [x] Task: Synchronize setting with Web Settings UI.
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)

## Phase 2: Android - Files & Video Screen Improvements
- [ ] Task: Implement `AutoResizingText` or dynamic font scaling for the path header.
- [ ] Task: Update folder long-press logic to include "Copy Path" in the context menu.
- [ ] Task: Update Image Viewer UI to include a filename label.
- [ ] Task: Refactor File Screen state management to ensure persistence and conditional text file re-opening.
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Phase 3: Android - AI Screen UX & Robustness
- [ ] Task: Implement `LaunchedEffect` or similar to scroll to bottom on screen entry and message send.
- [ ] Task: Debug and fix the AI screen "Focus" button logic.
- [ ] Task: Implement Model Selection dialog/trigger for Gemini CLI.
- [ ] Task: Create and integrate the Stopwatch UI component at the bottom of the chat.
- [ ] Task: Refactor "Thinking" indicator state to be persistent and robust across session switches.
- [ ] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)

## Phase 4: Finalization & Cleanup
- [ ] Task: Run `build_run_omnihub.py` to verify Hub changes.
- [ ] Task: Run `OmniSync.Android/build_and_deploy.py` to verify Android changes.
- [ ] Task: Final project-wide linting and check.
- [ ] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md)
