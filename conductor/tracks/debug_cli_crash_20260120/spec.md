# Specification: Debug CLI Initialization Crash

## Overview
This track focuses on diagnosing and fixing an inconsistent issue where the Gemini CLI session initializes but instantly closes. This behavior has been observed intermittently.

## Status (2026-01-20)
-   **Current State:** The issue is currently **dormant**.
-   **Observation:** After adding extensive logging and restarting the Hub, the `roundtrip_test.py` passes successfully and the CLI launches without issue.
-   **Hypothesis:** The issue may be related to a stale state in the Hub or a zombie process that was cleared during the restart/rebuild process.
-   **Next Steps:** The system has been fully instrumented. We are now in a "Monitor and Wait" phase. The next time the crash occurs, the new logs (specifically `ai_listener_crash.log`, `omni_cli_crash.log`, and the enhanced Hub logs) should provide the root cause.

## Goals
1.  **Instrument the System:** Add extensive, persistent logging across the entire call chain (Hub, CLI, Launch Scripts) to capture the state immediately preceding the crash. **(COMPLETED)**
2.  **Reproduce the Issue:** Use the `roundtrip_test.py` to trigger the crash while capturing detailed logs. **(PENDING RECURRENCE)**
3.  **Identify Root Cause:** Analyze the logs to determine if the failure originates in the Hub (SignalR/Session management), the Python Client (Initialization/Connection), or the environment (Process spawning).
4.  **Fix:** Implement a resolution for the identified root cause.

## Scope
### In Scope
-   **OmniSync.Hub:** Enhancement of session lifecycle logging (Connect/Disconnect/Errors).
-   **OmniSync.Cli:** Enhancement of startup sequence and exception handling logging.
-   **Test Scripts:** Modification of `roundtrip_test.py` or launch scripts if necessary to preserve output streams on failure.

### Out of Scope
-   Refactoring unrelated parts of the Hub or CLI.
-   New feature development.

## Reproduction Steps
1.  Run `python TestScripts/AIFeature/roundtrip_test.py`.
2.  Observe if the session window opens and immediately closes.
3.  Check generated logs for errors or unexpected termination signals.

## Success Criteria
-   The crash is either reliably reproduced with a clear error message OR the logging is sufficient to diagnose it the next time it happens.
-   The root cause is identified and fixed.