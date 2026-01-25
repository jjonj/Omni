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
- [x] Task: **WAIT FOR RECURRENCE.** 
    -   *Update 2026-01-24:* Hypothesis formed based on `hub_log.txt`. Fix implemented to guard discovery during launch. However, verification is pending next morning's typical failure window.
    -   *Update 2026-01-25:* Issue recurred. `hub_log.txt` confirms that even with the `_isLaunching` guard, `DiscoverSessionsAsync` (triggered by `GetAiSessions` from a client) still incorrectly identified a "wrapper" and disposed of the active session.
- [x] Task: Upon recurrence (if it occurs), collect:
    -   `hub_log.txt` (Hub side) [COLLECTED 2026-01-25]
    -   `gemini_cli_debug.log` (CLI stdout/stderr) [NOT FOUND]
    -   `ai_listener_crash.log` (Python exception log) [NOT FOUND]
    -   `omni_cli_crash.log` (Python exception log) [NOT FOUND]

## Phase 3: Analysis & Fix (Updated 2026-01-25)
- [x] Task: Analyze the gathered logs to identify the specific exception or exit code causing the crash.
    - *Result 2026-01-25:* Recurrence confirmed. `hub_log.txt` showed that `DiscoverSessionsAsync` was still disposing of valid sessions. It incorrectly flagged a session as a "wrapper" because it found a leaf child, even if the parent was already connected.
- [x] Task: Create a minimal failing test case or reproduction script if `roundtrip_test.py` is inconsistent, to confirm the root cause (Red Phase).
    - *Note:* Analysis was definitive; the logs showed the disposal happening while `_isLaunching` was false (triggered by a UI refresh).
- [x] Task: Implement the fix for the identified issue (Green Phase).
    - *Fix:* In `AiCliService.cs`, prioritized the `IsConnected` check. A connected session is now explicitly excluded from wrapper deduplication. Also enhanced logging to show exactly which child process triggers a wrapper removal.
- [x] Task: Verify the fix using `roundtrip_test.py` and ensure the session remains stable.
    - *Result 2026-01-25:* Roundtrip test passed. Log analysis confirms sessions are no longer disposed of during discovery if connected.

## Phase 4: Verification & Cleanup (Pending)

- [~] Task: **STALL UNTIL 2026-01-26 MORNING.**

    -   *Note:* The Hub restart clears the WMI/Process environment. We must wait for the system to run for >24 hours to ensure no other race conditions exist in the discovery/disposal loop. **Debugging is halted for the day.**


- [ ] Task: Review added logging. Decide which logs to keep for long-term observability and which to remove.
- [ ] Task: Remove any temporary hacks or verbose debug prints used solely for this investigation.
