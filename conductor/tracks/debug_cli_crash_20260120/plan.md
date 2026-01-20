# Implementation Plan - Debug CLI Initialization Crash

## Phase 1: Instrumentation (Completed)
- [x] Task: Add verbose file logging to `OmniSync.Hub` specifically around the `GeminiSession` initialization, SignalR connection events, and process spawning logic. [commit: Pending]
- [x] Task: Add verbose file logging to `OmniSync.Cli` (`ai_listener.py` / `omni_cli_script.py`) to catch unhandled exceptions at startup and log them to a persistent file immediately. [commit: Pending]
- [x] Task: Modify `launch_gemini_cli_hub.py` (or relevant launcher) and `AiCliService.cs` to capture and log the `stdout` and `stderr` of the spawned CLI process, preventing immediate window closure masking the error. [commit: Pending]

## Phase 2: Observation & Trap Setting (Current)
- [~] Task: Validate instrumentation with a roundtrip test.
    -   *Result 2026-01-20:* Roundtrip passed. Issue not reproduced. Hub restart likely cleared the bad state.
- [ ] Task: **WAIT FOR RECURRENCE.** Do not proceed until the crash is observed again.
- [ ] Task: Upon recurrence, collect:
    -   `hub_log.txt` (Hub side)
    -   `gemini_cli_debug.log` (CLI stdout/stderr)
    -   `ai_listener_crash.log` (Python exception log)
    -   `omni_cli_crash.log` (Python exception log)

## Phase 3: Analysis & Fix (Pending Diagnosis)
- [ ] Task: Analyze the gathered logs to identify the specific exception or exit code causing the crash.
- [ ] Task: Create a minimal failing test case or reproduction script if `roundtrip_test.py` is inconsistent, to confirm the root cause (Red Phase).
- [ ] Task: Implement the fix for the identified issue (Green Phase).
- [ ] Task: Verify the fix using `roundtrip_test.py` and ensure the session remains stable.

## Phase 4: Cleanup
- [ ] Task: Review added logging. Decide which logs to keep for long-term observability and which to remove.
- [ ] Task: Remove any temporary hacks or verbose debug prints used solely for this investigation.
