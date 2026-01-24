# Specification: Debug CLI Initialization Crash

## Overview
This track focuses on diagnosing and fixing an inconsistent issue where the Gemini CLI session initializes but instantly closes. This behavior has been observed intermittently.

## Status (2026-01-24 Update)
- **Observation:** Analysis of `hub_log.txt` revealed that when a new Gemini CLI session is launched (e.g., PID 26396), the `DiscoverSessionsAsync` background task incorrectly identifies it as a "wrapper" process. 
- **Hypothesis:** This occurs because the discovery logic finds another Gemini process (e.g., PID 11892) that incorrectly reports the *new* PID as its parent (likely due to WMI stale data or PID reuse). The Hub then calls `Dispose()` on the new session, closing its pipe and causing "Pipe is broken" errors in background communication tasks.
- **Fix Applied:** Modified `AiCliService.cs` to disable the "wrapper deduplication" logic while `_isLaunching` is true. This prevents the Hub from disposing of its own freshly-spawned session due to discovery race conditions or stale process metadata.

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