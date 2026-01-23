# Specification: Debug CLI Initialization Crash

## Overview
This track focuses on diagnosing and fixing an inconsistent issue where the Gemini CLI session initializes but instantly closes. This behavior has been observed intermittently.

## Status (2026-01-23)
-   **Current State:** The issue is currently **dormant** (re-cleared by Hub restart).
-   **Observation:** The crash occurs intermittently in the morning and persists until a Hub restart. It appears to "reset" or return every 24 hours (next morning). Manually launching the CLI via `cli.exe --workspace D:/` works even when the Hub-spawned version fails.
-   **New Hypothesis:** The failure is likely in the Hub's process spawning environment or the `cmd.exe /K` handoff, rather than a crash in the CLI logic itself (since `/K` should keep the window open, but it "instantly closes"). Stale environment variables or shell state in the Hub process might be the culprit.
-   **Logging Strategy:** 
    -   Hub now clears `hub_log.txt` and `gemini_cli_debug.log` on every startup to ensure fresh diagnostic data.
    -   `GeminiPipe DEBUG` logging has been suppressed to reduce noise and allow easier identification of `INIT_DEBUG` events.

## Launch Details
The Hub launches the CLI using the following command pattern (via `Process.Start` with `UseShellExecute = true`):

```bash
cmd.exe /K "set GEMINI_DEBUG_LOG_FILE=[root]\gemini_cli_debug.log && title OMNI_GEMINI_INTERACTIVE && cd /d "[gemini-cli-dir]" && node bundle/gemini.js --workspace "[workspace]" --yolo"
```

- **Binary:** `node` (v20+)
- **Script:** `bundle/gemini.js`
- **Shell:** `cmd.exe` with `/K` (keep open) to preserve the window if the inner command fails.


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