# Local service recovery

Read this before investigating OmniHub, OmniMCP-Lite, Tailscale, sleep/resume
failures, or repeated connectivity alerts. Diagnose the failing layer, preserve
evidence, make one targeted repair, then verify real calls. A running process or
an enabled Funnel configuration does not prove that remote access works.

## Components and ownership

| Component | Authority / check | Recovery |
| --- | --- | --- |
| Legacy C# OmniHub / Gateway | `OmniSync Hub` scheduled task; `http://127.0.0.1:3333/gateway/api/tools` and `/gateway/api/sweep` | Existing task and [Hub watchdog](../OmniSync.Hub/docs/OMNIHUB_WATCHDOG.md) |
| Rust OmniHub | Separate `omnihub.exe`; inspect its actual executable and launch configuration | Coordinate with agents working on Rust; restarting C# does not update Rust |
| Standalone OmniMCP-Lite | `OmniMCP-Lite` scheduled task; localhost port **8771**, MCP initialize and `hello_world` | Existing task action selects the executable/version |
| Tailscale transport and public Funnel | Windows `Tailscale` service, CLI transport state, actual public MCP calls | Targeted service restart for sustained failures; preserve Funnel routes |
| Lite / Funnel supervisor | `OmniMCP-Lite Watchdog` scheduled task; `%LOCALAPPDATA%\OmniSync\lite-watchdog\` | Existing task, independent of either Hub |

Port **8770** is the retired Hub MCP endpoint. Failure there is expected; do not
enable it to repair standalone MCP. See [MCP reliability](../OmniSync.Hub/docs/MCP_RELIABILITY.md)
and [Gateway ownership](../OmniSync.Hub/docs/GATEWAY.md).

## Collect evidence first

Run from the Omni repository in PowerShell. Save relevant output and timestamps
before restarting anything. Do not print authentication URLs or credentials.

```powershell
$stateDir = Join-Path $env:LOCALAPPDATA 'OmniSync/lite-watchdog'
Get-Content (Join-Path $stateDir 'status.json')
Get-Content (Join-Path $stateDir 'watchdog.log') -Tail 12
Get-Service Tailscale
Get-ScheduledTask -TaskName 'OmniSync Hub', 'OmniHub Watchdog', 'OmniMCP-Lite', 'OmniMCP-Lite Watchdog' |
    Select-Object TaskName, State
Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match '^(OmniSync.Hub|omnihub|OmniMCP-Lite|tailscaled)\.exe$' } |
    Select-Object ProcessId, Name, ExecutablePath
```

Interpret the watchdog fields separately:

- `checkedAt` is the recent supervisor heartbeat, normally updated every five
  seconds. It does **not** mean the public test ran again.
- `publicCheckedAt` records the last completed public probe round, normally
  once a minute. `publicIngress` contains a result for each public address.
- `localHealthy=true` means actual local initialize and tool calls passed.
- `publicHealthy=false` can mean partial ingress degradation while local MCP
  and Tailscale remain healthy. Inspect every address and `publicError`.
- `publicFailures` counts consecutive failed public ingress probe rounds.
  Reading the same JSON file twice does not create two independent failures.
- `tailscale.authRequired=true` requires sign-in or machine authorization;
  service restart loops cannot resolve it.
- `paused=true` intentionally disables automatic recovery. Check who created the
  maintenance marker before removing it.

After sleep/resume, inspect Windows System events from
`Microsoft-Windows-Kernel-Power` and `Microsoft-Windows-Power-Troubleshooter`.
Correlate their times with the watchdog and `%ProgramData%\Tailscale\Logs`.
Do not infer a crash just because a remote client lost its connection.

## Verify local and public MCP independently

This bounded, read-only probe uses the same verified server identity and tool
response checks as the supervisor. It does not restart services or overwrite
supervisor status. Running the watchdog with `--once` while its normal instance
holds the mutex can exit without performing a new check; use this instead.

```powershell
@'
import http.client, json, sys
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0, r'D:\SSDProjects\Omni\OmniSync.Hub\scripts')
import omnimcp_lite_watchdog as w
w.check_mcp(lambda: http.client.HTTPConnection('127.0.0.1', 8771, timeout=3), '/mcp')
print('Local initialize + hello_world: OK')
with ThreadPoolExecutor(max_workers=6) as pool:
    print(json.dumps(dict(pool.map(w.check_public, w.public_addresses())), indent=2))
print(json.dumps(w.tailscale_status()))
'@ | python -
```

The public test resolves ingress addresses through public DNS-over-HTTPS,
connects directly to each address, preserves the hostname for TLS certificate
verification, and checks both `/mcp` and `/hello-mcp`. Ordinary hostname access
on this PC can resolve through MagicDNS to its private Tailscale address and
bypass public Funnel entirely. A successful private request does not verify
public ingress. Also call `hello_world` through the user's connected public
plugin when available.

## Choose the repair from the evidence

1. **Hub unavailable, Lite healthy:** repair only the Hub through its existing
   task/watchdog. Check the owner of port 3333 before stopping a process. Follow
   the Hub watchdog's maintenance/deployment procedure and verify Gateway APIs
   afterward. Keep a backup and a rollback path when replacing binaries.
2. **Local MCP calls fail:** inspect the `OmniMCP-Lite` task action and logs. If
   its process is absent, start that task. If it is stuck, stop and start that
   exact task. Do not launch a guessed versioned executable or restart a healthy
   Lite server to fix a public-only failure.
3. **Tailscale is stopped:** start the existing `Tailscale` service. Resolve
   authorization separately if required. Find `tailscale.exe` using PATH or the
   installed service directory; this PC uses `E:\Program Files\Tailscale`.
4. **Local MCP passes but public ingress fails:** inspect distinct public probe
   rounds. A single failed round can recover on the next round without repair.
   The supervisor restarts Tailscale after three failed ingress rounds, with a
   ten-minute retry limit. Check whether that repair already happened. For a
   sustained failure requiring manual recovery, use one
   `Restart-Service -Name Tailscale -ErrorAction Stop`, then recheck transport
   state and actual public calls. Allow reconnection and propagation to finish;
   do not stack repeated restarts while ingress addresses are recovering.
5. **DNS/internet checks fail:** inspect the local network first. The supervisor
   does not restart Tailscale merely because public DNS resolution failed.

Funnel routes normally survive service restarts. Avoid `tailscale funnel reset`
as a routine repair: it removes all routes, including unrelated services.
If evidence requires changing routes, first save `tailscale funnel status --json`,
preserve every handler and port, and compare the final configuration to the
backup. Do not log out, delete Tailscale state, reauthorize the node, disable
firewalls, or alter tailnet permissions as speculative recovery steps.

Stop once appropriate checks pass. Report whether access is fully healthy,
partially degraded, or unverified. A pass is a point-in-time observation, not a
guarantee that an intermittent external path is permanently fixed.

## Connectivity popups and intermittent failures

Determine which application owns the popup. C# and Rust are separate processes.
The legacy C# service defers presentation only when
`lite-watchdog\rust-presentation.json` contains a fresh Rust lease and a matching
live process. A stale lease does not disable the C# fallback. Check the live DLL
hash/version as well as source files before claiming a fix is deployed.

The C# policy requires **three actual failed public ingress rounds** before
alerting. One-minute public results must not be confirmed by repeated five-second
reads. A confirmed outage sounds once; closing or dismissing it silences that
incident while health monitoring and automatic recovery continue. Recovery must
stay healthy for **two minutes** before resolving the episode, so a brief pass
does not immediately rearm a dismissed alert. Pending degradation keeps an
existing incident open. Recovery presentation is silent.

Keep equivalent behavior in any Rust presenter before deploying it. Do not
silence symptoms by stopping the watchdog, fabricating healthy status, treating
one successful ingress address as full health, or leaving maintenance enabled.

Regression tests for the C# policy:

```powershell
dotnet test OmniSync.Hub/src/OmniSync.Hub.Tests/OmniSync.Hub.Tests.csproj --filter FullyQualifiedName~ConnectivityAlertStateTests
```

## Observations from 2026-10-16

- After sleep/resume at 17:59 local time, Lite stayed healthy while public Funnel
  TLS handshakes failed despite Tailscale reporting online. Service recovery
  restored some addresses first; all six passed by 18:13. The exact transport
  cause was not established. The incident does not prove that Lite crashed.
- At 18:42, one IPv4/IPv6 ingress pair timed out after many healthy rounds. The
  18:43 round and direct tests passed all six addresses without another service
  restart. This exposed the cached-sample confirmation bug in the alert policy.
- Tailscale log lines saying `Drop ... no rules matched` are not sufficient
  evidence of broken Funnel permissions. A Tailscale maintainer explains that
  successful ingress also produces that line before the peer API accepts it:
  [upstream diagnostic explanation](https://github.com/tailscale/tailscale/issues/21752).
  Check the complete connection outcome rather than changing permissions based
  on that line alone.

## Temporary public MCP fallback, 2026-10-17

After the 12:02 boot, Lite auto-started and passed local MCP calls. All six known
public Funnel ingress addresses also passed initialize and hello_world with TLS
hostname verification, while Google, Cloudflare, and all four authoritative DNS
servers returned NXDOMAIN for the configured Tailscale hostname. One Tailscale
service restart, a bounded Funnel advertisement refresh, and a reconnect of the
existing authenticated session did not restore its public DNS. All Funnel routes
were preserved exactly. This differs from the TLS failures observed on October 16.

The user authorized a temporary replacement public URL through Cloudflare. A
hidden `cloudflared` process forwards to the existing `http://127.0.0.1:8771`
server; its public MCP path is `/mcp`. The official Windows executable's release
digest and Authenticode signature were verified before launch. The existing
private **omni mcp** account plugin was updated to use this endpoint (v0.5.1).
The separate App-backed **OmniMCP-Lite** connection retains its original URL;
editing that App-owned connection is not supported by the plugin archive editor.

Machine-local operational files are under
`%LOCALAPPDATA%\OmniSync\public-mcp-recovery\`:

- `endpoint.json`: the current public URL and verification result. Keep the actual
  privileged endpoint out of source-controlled documentation and registry metadata.
- `launch.json`: executable, argument tokens, process identity, and plugin details.
- `tunnel.log`, `stdout.log`, `stderr.log`, `tunnel.pid`: process diagnostics.
- `source-backup.json`: the original private plugin source and release for rollback.

This is a manually started **Quick Tunnel**, not an always-on service or a new
scheduled task. Its URL stops working when the process exits, and a new launch
gets a different hostname. Do not restart a healthy tunnel or promise that its
URL survives reboot. If it must be relaunched, use the saved launch definition,
start it hidden, read the newly issued URL from its logs, verify real public MCP
calls, and update the selected plugin connection to that URL. Before stopping it,
match its executable path and creation time against `launch.json`; do not trust
a reused PID alone. The original Tailscale routes and watchdog remain enabled.
Their `publicHealthy` field continues to describe Tailscale, not the fallback.

Cloudflare Quick Tunnel behavior and limitations:
[official documentation](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/).
