# OmniMCP-Lite recovery

For the operational checklist covering Hub, Lite, Tailscale, and connectivity
alerts, read [Service recovery](../../docs/SERVICE_RECOVERY.md).

OmniMCP-Lite runs independently on 127.0.0.1:8771. The deprecated Hub MCP
listener on 8770 stays disabled. A connection failure there is expected.

## Supervisors

- Hub's `OmniMcpLiteWatchdogService` retains its local health fallback.
- `OmniHub Watchdog` independently supervises the Hub.
- `OmniMCP-Lite Watchdog` independently supervises Lite **and the public
  Tailscale Funnel path**. It does not require the Hub to be running.

Install/reinstall the third task from an elevated PowerShell:

```powershell
& D:\SSDProjects\Omni\OmniSync.Hub\scripts\install_omnimcp_lite_watchdog.ps1
```

It uses the real Python installation's pythonw.exe (no console window), starts
at logon, and has an unlimited one-minute scheduler repetition to recover if
the supervisor dies. Task Scheduler and a process mutex prevent duplicates.
The desktop server retains its interactive user session for desktop tools.
Lite's existing scheduled-task executable/version remains authoritative;
installation backs up its task XML before repairing task restart settings.

Local checks every five seconds validate `initialize.serverInfo.name` and
an actual `hello_world` tool result. Three failures restart the Lite task;
attempts are limited to once per minute. Every minute public checks fetch
public DNS using DoH, then connect to **each public IPv4/IPv6 ingress address**
with the original TLS SNI/certificate verification. This bypasses MagicDNS,
which otherwise resolves this hostname to its private tailnet address and
can falsely report success while public Funnel is broken. Both `/mcp` and
`/hello-mcp` are checked. Three public failures with healthy local MCP restart
the Tailscale Windows service, at most once per ten minutes. DNS lookup
failures alone never trigger service restarts. Existing Funnel routes persist.

Status and rotating diagnostic logs:

```text
%LOCALAPPDATA%\OmniSync\lite-watchdog\status.json
%LOCALAPPDATA%\OmniSync\lite-watchdog\watchdog.log
```

Create `pause.txt` in that directory to suspend recovery during maintenance;
probes and diagnostic status continue. Remove it to resume. The Hub watchdog
has its own separate maintenance marker under `OmniSync\watchdog`.

Run deterministic recovery tests without restarting services:

```powershell
python TestScripts/McpServer/test_lite_watchdog.py
```

## 2026-10-16 outage

Local MCP health, initialize and tool calls succeeded while the public plugin
returned connection errors. Forced public ingress tests reproduced TLS EOF
and handshake timeouts, despite `tailscale funnel status` showing Funnel on.
Some ingress addresses worked while others failed. The existing Hub
watchdog could not detect this because it checked only localhost `/health`.
Restarting/reconnecting Tailscale restored the public plugin tool call.

The machine was authorized, with no AuthURL pending and key expiry in April
2027. Tailscale unattended mode was disabled and was enabled during repair.
The transient `invalid-packet-filter` warning during reconnect cleared in the
same startup sequence; it was not a persistent authorization failure.

Tailscale was upgraded from 1.102.4 to 1.104.1 through its signed official
updater. After the upgrade all three public IPv4 addresses and all three
IPv6 addresses passed initialize/tool checks. The public plugin also passed
real tool calls. A controlled live Lite process crash was recovered by the
independent supervisor in 19.3 seconds, with its recovery recorded in the log.
Killing the supervisor itself was recovered by Task Scheduler in 33.1 seconds;
the replacement reported local and all six public ingress checks healthy,
with maintenance mode off. Five deterministic recovery tests also passed.

Public ingress remains an external dependency: a local supervisor can detect
and retry recovery, but cannot guarantee availability of every relay. Treat
`publicHealthy=false` as degraded even when a particular client succeeds.

### Sleep/resume and alert repetition, 2026-10-16

Windows recorded sleep at 17:59:20 and wake at 17:59:35 local time. Lite
continued passing local initialize and hello_world calls. Public Funnel checks
began failing TLS handshakes at 18:01:45 despite Tailscale reporting online.
The watchdog restarted Tailscale at 18:03:47; manual reconnect and reapplication
of the saved Funnel routes followed. Public ingress recovered gradually, with
all six IPv4/IPv6 addresses passing by 18:13:11 and on subsequent checks. The
precise transport failure is not established; no Lite crash was observed.

The legacy Hub alert was explicitly configured to repeat with sound every
30 seconds and reopen one minute after closing. It now announces an outage
once. Dismiss or close hides it for the remainder of that outage, including
changes in the failure description. Recovery closes a dismissed alert silently;
an undismissed alert shows recovery without sound. Health probes and automatic
recovery remain enabled. A subsequent outage can alert again.

At 18:42 a single public probe round timed out on one ingress pair; the next
round at 18:43 passed all six addresses without another restart. The C# alert
policy had counted repeated reads of that cached failure as confirmation. It
now requires three actual failed public ingress rounds before alerting and two
minutes of sustained health before resolving an existing episode. Pending
degradation does not resolve or rearm an existing dismissed incident.
