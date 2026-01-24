# Implementation Plan - Debug CLI Initialization Crash

## Phase 1: Instrumentation (Completed)
- [x] Task: Add verbose file logging to `OmniSync.Hub` specifically around the `GeminiSession` initialization, SignalR connection events, and process spawning logic. [ef16e65]
- [x] Task: Add verbose file logging to `OmniSync.Cli` (`ai_listener.py` / `omni_cli_script.py`) to catch unhandled exceptions at startup and log them to a persistent file immediately. [ef16e65]
- [x] Task: Modify `launch_gemini_cli_hub.py` (or relevant launcher) and `AiCliService.cs` to capture and log the `stdout` and `stderr` of the spawned CLI process, preventing immediate window closure masking the error. [ef16e65]
- [x] Task: Implement auto-clearing of `hub_log.txt` and `gemini_cli_debug.log` in `Program.cs` to ensure fresh diagnostic state on Hub restart. [7eb3a8a]
- [x] Task: Suppress high-volume `GeminiPipe DEBUG` logs in `AiCliService.cs` to allow clear visibility of `INIT_DEBUG` events in production logs. [7eb3a8a]

## Phase 2: Observation & Trap Setting (Current)
- [x] Task: Validate instrumentation with a roundtrip test.
    -   *Result 2026-01-20:* Roundtrip passed. Issue not reproduced. Hub restart likely cleared the bad state.
- [~] Task: **WAIT FOR RECURRENCE.** 
    -   *Update 2026-01-24:* Hypothesis formed based on `hub_log.txt`. Fix implemented to guard discovery during launch. However, verification is pending next morning's typical failure window.
- [ ] Task: Upon recurrence (if it occurs), collect:
    -   `hub_log.txt` (Hub side)
    -   `gemini_cli_debug.log` (CLI stdout/stderr)
    -   `ai_listener_crash.log` (Python exception log)
    -   `omni_cli_crash.log` (Python exception log)

## Phase 3: Analysis & Fix (Completed 2026-01-24)
- [x] Task: Analyze the gathered logs to identify the specific exception or exit code causing the crash.
    - *Result:* Identified a race condition in `DiscoverSessionsAsync` where new sessions were incorrectly flagged as wrappers and disposed.
- [x] Task: Create a minimal failing test case or reproduction script if `roundtrip_test.py` is inconsistent, to confirm the root cause (Red Phase).
    - *Note:* Analysis was definitive enough from `hub_log.txt` showing the "Removing wrapper session" log immediately followed by the crash.
- [x] Task: Implement the fix for the identified issue (Green Phase).
    - *Fix:* Guarded wrapper deduplication with `!_isLaunching`.
- [ ] Task: Verify the fix using `roundtrip_test.py` and ensure the session remains stable.

## Phase 4: Cleanup
- [ ] Task: Review added logging. Decide which logs to keep for long-term observability and which to remove.
- [ ] Task: Remove any temporary hacks or verbose debug prints used solely for this investigation.
