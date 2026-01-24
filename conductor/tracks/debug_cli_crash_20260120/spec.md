# Specification: Debug CLI Initialization Crash

## Overview
This track focuses on diagnosing and fixing an inconsistent issue where the Gemini CLI session initializes but instantly closes. This behavior has been observed intermittently.

## Status (2026-01-24 Update)
- **Observation:** Analysis of `hub_log.txt` showed the Hub explicitly logging `Removing wrapper session PID X (Leaf process discovered)` for the *exact PID* it had just successfully launched. This was immediately followed by `System.IO.IOException: Pipe is broken` when the Hub tried to send the initial prompt.
- **Hypothesis (The Discovery Race):** 
    1. The Hub launches a new Gemini process.
    2. Before the launch sequence is "complete" in the Hub's state machine, the background `DiscoverSessionsAsync` task triggers.
    3. WMI returns the new PID and a "phantom/stale" PID from a previous failed run.
    4. The deduplication logic sees a parent-child relationship (potentially due to PID reuse or stale WMI metadata) and concludes the new PID is just a "wrapper."
    5. The Hub calls `Dispose()` on the new session, closing the named pipe.
    6. **Take on Stack Traces:** The `Pipe is broken` stack traces are *not* the cause of the crash; they are the symptom of the Hub trying to use a pipe it just closed itself.
- **Fix Applied:** Added a guard in `AiCliService.cs` to skip wrapper deduplication while `_isLaunching` is true.
- **Caution:** Since the Hub restart clears the environment, we cannot confirm this fix until the system has been running for >24 hours (the typical recurrence window).

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