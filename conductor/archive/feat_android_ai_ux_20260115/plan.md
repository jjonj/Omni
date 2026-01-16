# Implementation Plan: Android AI Chat UX Improvements [checkpoint: complete]

## Phase 1: Android Auto-Scroll & Status Logic
- [x] Task: Create `TestScripts/AIFeature/test_android_ux.py` (or similar) to simulate SignalR messages for dialogs and status updates, verifying Android client behavior.
- [x] Task: Implement `Auto-Scroll Fix` in `AiChatScreen.kt`.
    - [x] Modify `LazyColumn` state handling to reliably scroll to bottom on new user message.
    - [x] Fix the "Snap to Bottom" fab/trigger logic to be responsive even when near-bottom.
- [x] Task: Implement `Immediate Thinking State`.
    - [x] Update `SignalRClient.kt` or `AiChatScreen.kt` to set local "Thinking" state immediately upon `sendAiMessage`, clearing it only when response completes or fails.
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)

## Phase 2: Dialog Alerts & Notifications
- [x] Task: Implement `Sound Notification` for Dialogs.
    - [x] Add a subtle sound effect resource to Android project. (Used ToneGenerator)
    - [x] Trigger sound in `SignalRClient` or `MainActivity` when `AiDialog` is received.
- [x] Task: Implement `Session Button Highlighting`.
    - [x] Update `AiChatScreen.kt top bar to flash/highlight the session icon when `AiDialog` state is active.
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Phase 3: Hub Window Management
- [x] Task: Update `OmniSync.Hub/.../ProcessService.cs` (or `WindowDetector.cs`).
    - [x] Add logic to check `IsIconic` (minimized) via P/Invoke.
    - [x] If minimized, call `ShowWindow(SW_RESTORE)` before `SetForegroundWindow`.
- [x] Task: Verify `FocusAiSession` in `AiCliService.cs` uses this new logic.
- [x] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)
