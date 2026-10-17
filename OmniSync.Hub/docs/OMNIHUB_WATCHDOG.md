# Independent OmniHub watchdog

Implemented and installed on Windows on 2026-10-15.

## Why an external supervisor?

The Hub cannot recover from its own termination/hang. The existing **OmniSync Hub**
Task Scheduler task launches the Hub at logon but previously stopped the process
after 72 hours, had no restart-on-failure policy, and was not an independent
health monitor. The Hub-internal `OmniMcpLiteWatchdogService` monitors Lite, not
the Hub itself.

The **OmniHub Watchdog** scheduled task runs a separate Windows PowerShell
process, completely outside OmniHub, which checks both the owner of port 3333
and the real Gateway API. It never launches a second Hub if the expected Hub is
already responsive. It does not depend on OmniMCP-Lite or the original Hub MCP.

## Recovery policy

- Poll every **5 seconds**, HTTP timeout **2 seconds**.
- If the expected OmniHub process disappears, attempt restart immediately on the
  next poll. If running but nonresponsive, require **2 failed probes**.
- Verify the process listening on port 3333 is the **exact executable path**
  defined by the existing `OmniSync Hub` scheduled task. Never kill foreign
  processes on the port.
- For recovery, stop the Hub scheduled task, terminate only matching Hub
  executables, then launch the existing task. The scheduled task remains the
  source of truth for the executable, permissions and startup flags.
- Allow **15 seconds** for startup before another recovery attempt, limiting
  destructive restart loops. Recovery exceptions are logged, not fatal.
- Store heartbeat in
  `%LOCALAPPDATA%\OmniSync\watchdog\status.json` and capped, rotated history in
  `%LOCALAPPDATA%\OmniSync\watchdog\watchdog.log`.
- Task Scheduler runs the watchdog **at logon**, and retries **every minute**
  (ignores launches while running). It also attempts 3 direct restarts when the
  watcher exits unexpectedly. The minute trigger serves as ongoing self-repair
  if those retries fail.
- Both tasks have *unlimited execution time*, restart-on-failure enabled,
  recovery after missed starts, and no stop-on-battery policy. A user must be
  signed in for this interactive Hub configuration.

## Installation and administration

Run elevated:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File D:\SSDProjects\Omni\OmniSync.Hub\scripts\install_omnihub_watchdog.ps1
```

Verify:

```powershell
Get-ScheduledTask -TaskName 'OmniHub Watchdog', 'OmniSync Hub'
Get-Content "$env:LOCALAPPDATA\OmniSync\watchdog\status.json"
Get-Content "$env:LOCALAPPDATA\OmniSync\watchdog\watchdog.log" -Tail 20
```

To intentionally stop or rebuild the Hub without watchdog interference,
create an explicit maintenance marker before taking down the process:

```powershell
New-Item -ItemType File "$env:LOCALAPPDATA\OmniSync\watchdog\pause.txt" -Force
# stop/rebuild/start Hub
Remove-Item "$env:LOCALAPPDATA\OmniSync\watchdog\pause.txt"
```

To disable permanently, disable **OmniHub Watchdog** in Task Scheduler and
stop its running task (disabling alone does not stop the current watcher). To
resume: enable it and start it. Task Scheduler may restart the Hub itself up
to 3 times independently, even while the watchdog is paused.

For safe non-destructive probes of the supervisor:

```powershell
# Before starting the persistent task, or after stopping it:
.\omnihub_watchdog.ps1 -Once
.\omnihub_watchdog.ps1 -Once -DryRun -FailureThreshold 1 -ProbeUrl http://127.0.0.1:3333/nonexistent
```

## Validation performed 2026-10-15

- Healthy endpoint and process-owner verification: **PASS**.
- Dry-run against a known HTTP 404: **PASS**; emitted recovery action.
- Killed running Hub PID 44836 as a controlled fault injection. Watchdog
  launched replacement Hub PID 26100 and the Gateway API returned 3 tools
  within **5.7 seconds**: **PASS**.
- Windows scheduled watchdog is installed and running as an independent
  task, with 2 triggers (logon + minute retry).
- Killed running watchdog PID 39024 as a second controlled fault injection.
  Windows Task Scheduler relaunched the supervisor as PID 19116 and it reported
  healthy after **20.1 seconds**: **PASS**.
