# Implementation Plan - Fix AI Message Queuing during Launch

## Phase 1: Diagnosis & Regression
- [~] Task: Reproduce the queuing failure using `roundtrip_test.py` and analyze `pipe_debug.log`.
- [ ] Task: Add enhanced diagnostic logging to `AiCliService.cs` specifically around the `_pendingPrompts` queue and its interaction with `ConnectAsync`.
- [ ] Task: Conductor - User Manual Verification 'Diagnosis & Regression' (Protocol in workflow.md)

## Phase 2: Non-Admin Process Spawning
- [ ] Task: Modify `AiCliService.cs` to spawn Gemini CLI instances as a standard user (non-admin) even if the Hub is elevated.
- [ ] Task: Verify process elevation levels using `tasklist` or Process Explorer to confirm CLI instances are non-admin.
- [ ] Task: Conductor - User Manual Verification 'Non-Admin Process Spawning' (Protocol in workflow.md)

## Phase 3: Queuing System Fix
- [ ] Task: Write a unit test in `OmniSync.Hub.Tests` that simulates a delayed pipe connection and verifies messages are queued and delivered.
- [ ] Task: Refactor `AiCliService.cs` to ensure the `_pendingPrompts` queue is processed atomically and only after the `GeminiSession` is fully connected.
- [ ] Task: Verify the fix with the newly created unit tests.
- [ ] Task: Conductor - User Manual Verification 'Queuing System Fix' (Protocol in workflow.md)

## Phase 4: Integration & Cleanup
- [ ] Task: Run `roundtrip_test.py` multiple times to ensure the fix is robust and consistent.
- [ ] Task: Remove or consolidate temporary diagnostic logging in `AiCliService.cs`.
- [ ] Task: Conductor - User Manual Verification 'Integration & Cleanup' (Protocol in workflow.md)
