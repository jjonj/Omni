# OMN-0085 — Flight-grade Android → OmniHub transport reproduction

This harness sends **real SignalR requests to the running Rust OmniHub** through a locally scoped, disposable TCP impairment proxy. It is not a mocked Hub. An optional disposable Android emulator runner installs and starts the **real debug APK** through the same proxy. No OS routes, production Hub configuration, firewall rules, Tailscale settings or home router settings are modified.

## Quick reproduction (Windows)

From `D:\SSDProjects\Omni` with Node 22+, a healthy Rust OmniHub on 127.0.0.1:5000, and its current key in `%LOCALAPPDATA%\OmniHub\config\appsettings.json`:

```powershell
node TestScripts/FlightLink/flight-link-run.cjs --out E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/flight-final
node TestScripts/FlightLink/flight-link-run.cjs --full --out E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/flight-extended
```

The runner starts/stops an isolated loopback listener, performs **HTTP POST /signalrhub/negotiate → WebSocket upgrade → SignalR JSON handshake → Authenticate → GetAvailableDrives**, and records each command. No remote-control/power/clipboard commands or mutations are issued. A command's `invocationId` is correlated across client and proxy trace, including cases where the Hub completed it but the completion never reached the client.

Artifacts in `--out`: `commands.jsonl` (client timings/reasons); `proxy.jsonl` (HTTP/WS metadata and Hub-side receipts without payloads/API keys); `delivery-ledger.jsonl` (each command's observed client outcome and whether Hub delivery can be proved); `summary.json`. **`hubDelivery: unknown` does not mean the Hub did not execute the command**. It means no definitive completion was observed; blindly retrying non-idempotent commands would be unsafe.

Profiles (the values are **synthetic test parameters**, not measurements from Air China or any route):

| Profile | Nominal RTT | Jitter | Downstream bandwidth | Loss injection |
|---|---:|---:|---:|---|
| clean | 0 ms | 0 | unlimited | 0 |
| slow300 | 300 ms | ±75 ms | 64 kb/s | 1% |
| slow800 | 800 ms | ±200 ms | 32 kb/s | 3% |
| slow2000 | 2000 ms | ±500 ms | 16 kb/s | 10% |
| noUdp | 800 ms | ±200 ms | 32 kb/s | 0; TCP-only application transport |
| route_change | disconnection, then slow300 | — | — | explicit socket reset |
| outage10 | 10s total outage; recovery after link up | — | — | all active sockets closed |
| outage120 | 120s total outage (`--full`) | — | — | all active sockets closed |

The built-in proxy **does not drop individual TCP packets**. To avoid silently corrupting a reliable byte stream, `lossPct` models disruption as seeded connection resets with a per-~1400-byte probability. This is good for observing **uncertain delivery/reconnect**, but **not** for measuring TCP retransmissions, congestion response or Tailscale path election. `kbps` shapes buffered TCP chunks and both directions; it is not radio bandwidth. The optional Linux Docker `netem/` mode below can apply actual network-layer loss.

### Interactive path and real Android APK

Use separate shells. A proxy on 127.0.0.1:15000 forwards to **existing** Rust Hub 127.0.0.1:5000; control on 127.0.0.1:15001:

```powershell
node TestScripts/FlightLink/flight-link-proxy.cjs --profile slow800 --trace E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/android-proxy.jsonl
# On an existing disposable x86-64 Android emulator, after the APK permissions are accepted:
node TestScripts/FlightLink/flight-link-android.cjs --serial emulator-5554 --profile slow800 --seconds 35
Invoke-RestMethod http://127.0.0.1:15001/outage -Method Post -ContentType application/json -Body '{"seconds":10}'
Invoke-RestMethod http://127.0.0.1:15001/switch -Method Post -ContentType application/json -Body '{"profile":"slow300"}'
node TestScripts/FlightLink/flight-link-report.cjs --trace E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/android-proxy.jsonl --out E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/android-report
```

The separate `flight-link-report.cjs` combines each recorded Android invocation and Hub completion into a redacted per-command `android-delivery-ledger.jsonl` and `android-summary.json`. It preserves `hubReceipt: unknown` for fire-and-forget invocations and interrupted calls rather than incorrectly claiming success/failure.

The Android runner **refuses a physical-device serial**. It uses `adb reverse tcp:15000 tcp:15000`, installs the existing debug APK, changes only the disposable emulator's saved `hubUrl`, `wanIp` and `apiKey`, and captures filtered SignalR logs. Its profile uses the **actual Android SignalR client** with real `/signalrhub`, `/terminalhub` and `/remoteterminalhub` requests; unlike `flight-link-run`, no protocol emulator substitutes for app code. Launch an Android Files screen and refresh drives to exercise an explicit read-only UI command (the headless script does not tap UI automatically). It requires a working, booted emulator and any first-run Android permission dialogs to be handled. Stop with `adb -s emulator-5554 shell am force-stop com.omni.sync`, `adb -s emulator-5554 reverse --remove tcp:15000`, and Ctrl+C for the proxy. The task's disposable AVD was stored in `E:/_AgentScratch/Tasks/Omni/OMN-0085/Temporary/avd`; nothing was installed on the user's phone.

For a **manually chosen** physical-phone LAN test, run `--listen 0.0.0.0` only on a trusted LAN, point the app's Hub URL at `http://<PC LAN IP>:15000/signalrhub`, and restore the phone's normal URL afterward. Beware: the existing Android `updateHubUrlBasedOnLocalIp()` can rewrite that URL during reconnect. Do **not** expose port 15000 to the public internet or treat this proxy as an authentication layer.

**Current overlay observation:** read-only `tailscale status --json` reported local backend `Running` with 23 known peers but no currently active peer paths at inspection time. It therefore establishes **neither a direct UDP path nor a DERP relay path to this phone**. When the Android device is online, inspect `tailscale status --json` and `tailscale ping <device>` or supported `tailscale netcheck` from the endpoint without changing configuration; record direct/relay transitions alongside these app traces.

**HTTPS-only simulation**: `flight-link-proxy.cjs --tls-cert <pem> --tls-key <pem> --profile httpsOnly` terminates client TLS, then forwards plaintext to the real local Hub. The Android client must trust the supplied cert and use `https://<test host>:15000/signalrhub`. The default runner uses cleartext loopback, so HTTPS-only cannot be claimed tested without a certificate, DNS and corresponding Android trust/path setup. A real aircraft firewall, DNS interception, Chinese blocking, or Tailscale DERP negotiation is **not** emulated by a `noUdp` TCP-only profile.

### Optional actual packet loss in a disposable container

On a Linux Docker Engine with `NET_ADMIN` capability, the provided `netem/Dockerfile` shapes only **the container's eth0**, not Windows production adapters. Docker is not currently installed on the test PC; this path is supplied for repeatability but was not executed here.

```sh
docker build -t omni-flight-netem TestScripts/FlightLink/netem
docker run --rm --cap-add=NET_ADMIN -p 127.0.0.1:15002:15000 \
  -e RTT_MS=800 -e JITTER_MS=200 -e LOSS_PCT=3 -e RATE_KBIT=32 \
  omni-flight-netem
```

Point a test SignalR client at port 15002; `socat` forwards to `host.docker.internal:5000`. The Hub needs to be reachable from the container (on some Docker engines `host.docker.internal` needs explicit `--add-host host.docker.internal:host-gateway`). TCP behavior in the container uses actual `tc netem` loss/delay; it does **not** automatically capture the application-level per-command ledger, so pair with a separate app-level observer if that evidence is needed.

## Measured observations (local synthetic impairment; 2026-11-16)

Read the exact redacted evidence at `E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/flight-final` and the independently rerun `flight-final-isolated`. These were against the **live Rust Hub** without restarting or modifying it, using separate local test ports. The timings below are from `flight-final` (one seeded run, not airline reliability statistics).

| Scenario | Negotiated & authenticated | Read-only GetAvailableDrives | Finding |
|---|---:|---:|---|
| clean | 0.34s | 0.08s | works |
| slow300 | 1.63s | 2.59s | works, substantially delayed |
| slow800 | 4.01s | 5.23s | works |
| slow2000 | 8.81s | client failed at +1.50s, WS 1006 | abrupt loss/unknown delivery under 10% reset profile |
| noUdp (TCP protocol only) | 7.98s | 9.72s | app-level TCP works; **not** a Tailscale DERP test |
| route switch → slow300 | recovered in 3.83s | stable drive identity | converged |
| 10s outage | recovered 0.73s after simulated link up | stable drive identity | converged; attempted command during outage not sent |

`summary.json` from that run: **19** invocation attempts; **17** confirmed Hub receipts, **2** client failures, including **1 not sent** and **1 delivery unknown**; **2/2** recovery cases converged. The later *isolated-port* rerun also converged **2/2** recovery cases and recorded **18** invocations, **17** Hub confirmations, **1 completion lost after the Hub completed**, **1 unknown-delivery command**, and **2 client-observed failures**. A separate earlier seeded run showed one command completed at the Hub despite no client completion, proving uncertain-delivery mode can happen. The pre-outage and post-reconnect comparisons intentionally exclude per-response `last_modified` values.

**Actual Android debug APK in a disposable x86-64 emulator** made real requests through the proxy including `/signalrhub/negotiate`, `/signalrhub`, `/terminalhub` and `/remoteterminalhub`. Crucially, Android's Java SignalR sends **JSON records inside binary WebSocket frames (opcode 2)**, now captured by the proxy. In `android-final-proxy.jsonl` and `android-final/android-delivery-ledger.jsonl`, a slow300/1%-reset run captured **10 real Android app startup invocations** (Authenticate, GetOmniAiMode, SendBrowserCommand, AdvertiseAssistantCommands, GetReminders, GetTaskResurfacingTimes): **5 confirmed Hub completions**, **2 interrupted awaiting completion**, **3 fire-and-forget without receipts**. The interrupted calls' Hub delivery remains unknown; the proxy intentionally reset a main SignalR socket. Subsequent Android logcat showed a **10-second failed connection to synthesized 10.0.2.37:5000** followed by repeated failed WAN fallback. This is a confirmed actual APK reconnect/fallback failure, not merely the Node protocol driver. The APK path was **background startup/read-only observations, not an automated tap of the phone Files UI**; a physical phone, flight path and Tailscale DERP behavior remain unverified.

## Prioritized engineering fixes

1. **P0 — Stop overwriting configured Hub URLs.** Android `SignalRClient.updateHubUrlBasedOnLocalIp()` assumes LAN Hub host `.37` whenever local phone Wi-Fi is in 10/8 or 192.168/16, destroying a user-configured Tailscale name, proxy, HTTPS origin or actual host. Restrict discovery to explicitly configured LAN mode, preserve user override across retries and network changes; show which candidate was selected. This was observed on the emulator.
2. **P0 — Give commands stable IDs + durable statuses at both ends.** Many Android `hubConnection.send(...)` calls are fire-and-forget. A timeout/WS close means **delivery unknown**, not safely retryable. Persist command ID and Hub admission/completion receipts; for side effects use idempotency/dedup at Hub, and surface Sent → Hub received → Completed/Failed/Unknown to user.
3. **P1 — Mobile network change aware reconnect.** Prefer last successful endpoint, react to Android ConnectivityManager changes immediately, abort dead URL negotiation, reset old sockets, cap 10s × N sequential fallback stalls. Backoff up to 60s adds cost after a temporary outage; record *link restored → authenticated → state reloaded* separately.
4. **P1 — Check and tune timeouts by operation.** Android startup has a 10s timeout; real slow2000 TCP path authenticated in ~8.81s before a randomly induced disconnect. Avoid treating “connected” as “authenticated and state converged”; e.g., the app calls `authenticateClient()` asynchronously after `setConnected(true)`. Make timeouts context-sensitive and propagate explicit failure.
5. **P1 — Verify HTTPS-only/DERP in a separate endpoint-instrumented pass.** Run Android with trusted HTTPS front-end and WSS/LongPolling, then separately compare Tailscale direct UDP, DERP and blocked UDP using `tailscale status`/metrics (read-only). Captive portal and Chinese DNS/HTTPS filtering cannot be inferred from a localhost shaper.
6. **P2 — Reconcile state after reconnect.** Refresh the affected screen's snapshots, compare stable entity IDs/revisions, and surface stale data rather than silently claiming resumed. This harness checks read-only drive identity; it does not establish all UI state consistency.

No production networking was changed, no flight connectivity claims are implied, no phone configuration was modified, and no destructive RPCs were sent.
