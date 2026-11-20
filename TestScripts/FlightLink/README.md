# OMN-0085 — Flight-grade Android → OmniHub transport reproduction

This harness sends **real SignalR requests to the running Rust OmniHub** through a locally scoped, disposable TCP impairment proxy. It is not a mocked Hub. An optional disposable Android emulator runner installs and starts the **real debug APK** through the same proxy. The default isolated harness changes no OS routes, Hub configuration, firewall rules, Tailscale settings or home router settings. The separately opted-in live physical-phone WAN session uses a temporary router NAT-PMP lease and a dedicated temporary firewall rule, documented below.

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
| **flightChaos** | **300–4000 ms dynamically** | **varying** | **4–256 kb/s dynamically** | **0–45% degradation, randomized full 100% blackouts lasting 4–60 s** |
| noUdp | 800 ms | ±200 ms | 32 kb/s | 0; TCP-only application transport |
| route_change | disconnection, then slow300 | — | — | explicit socket reset |
| outage10 | 10s total outage; recovery after link up | — | — | all active sockets closed |
| outage120 | 120s total outage (`--full`) | — | — | all active sockets closed |

The built-in proxy **does not drop individual TCP packets**. In fixed profiles, `lossPct` models disruption as seeded connection resets with a per-~1400-byte probability. `flightChaos` instead generates a continuously changing moderate/degraded/severe/blackout state: per-chunk 300–4000 ms RTT, partial-loss-like retransmission stalls, occasional connection resets, and unpredictable **100% no-forwarding periods up to 60 seconds**. Most blackouts are short, some last 40–60 seconds; a blackout is selected at random after 45 seconds without one, and forced at the next phase transition after 120 seconds without one. It holds both directions' queued TCP bytes while the link is down and releases them in FIFO order afterward; the client may close/reconnect on its own timeout. This avoids corrupting WebSocket frames, but cannot measure actual TCP retransmits. The profile is intentionally very rough and **not calibrated to measured flight Wi-Fi performance**: airline, equipment, route, weather and restrictions vary widely. This is good for observing **uncertain delivery/reconnect**, but **not** for measuring TCP congestion or Tailscale path election. `kbps` shapes buffered TCP chunks and both directions; it is not radio bandwidth. The optional Linux Docker `netem/` mode below can apply actual network-layer loss.

### Interactive path and real Android APK

Use separate shells. A proxy on 127.0.0.1:15000 forwards to **existing** Rust Hub 127.0.0.1:5000; control on 127.0.0.1:15001:

```powershell
node TestScripts/FlightLink/flight-link-proxy.cjs --profile slow800 --trace E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/android-proxy.jsonl
# On an existing disposable x86-64 Android emulator, after the APK permissions are accepted:
node TestScripts/FlightLink/flight-link-android.cjs --serial emulator-5554 --profile slow800 --seconds 35
# After accepting first-run permissions in this disposable emulator, exercise the real Files UI:
node TestScripts/FlightLink/flight-link-files-ui.cjs --serial emulator-5554 --out E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/files-ui-run1.json
Invoke-RestMethod http://127.0.0.1:15001/outage -Method Post -ContentType application/json -Body '{"seconds":10}'
Invoke-RestMethod http://127.0.0.1:15001/switch -Method Post -ContentType application/json -Body '{"profile":"slow300"}'
# Start automatically cycling random flight impairment without touching Android:
Invoke-RestMethod http://127.0.0.1:15001/profile -Method Post -ContentType application/json -Body '{"profile":"flightChaos"}'
# Inspect current phase, measured commands and time left in a blackout:
Invoke-RestMethod http://127.0.0.1:15001/status
# Stop random changes and restore normal speed:
Invoke-RestMethod http://127.0.0.1:15001/profile -Method Post -ContentType application/json -Body '{"profile":"clean"}'
node TestScripts/FlightLink/flight-link-report.cjs --trace E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/android-proxy.jsonl --out E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/android-report
```

The separate `flight-link-report.cjs` combines each observed Android invocation, Hub completion, and socket-closure reason into a redacted per-command `android-delivery-ledger.jsonl` and `android-summary.json`. For each command, the ledger records the impairment phase/effective profile **at the proxy's first observation**, elapsed time to a Hub completion (if one was seen), the interruption reason, and whether Hub completion is confirmed or unknown. It preserves `hubReceipt: unknown` for fire-and-forget invocations and interrupted calls rather than incorrectly claiming the Hub did not execute them. A Hub completion proves Hub receipt **but not that the delayed response reached Android or appeared in the UI**. Phase association is contextual evidence, not proof the impairment caused the observed delay.

The Android runner **refuses a physical-device serial**. It uses `adb reverse tcp:15000 tcp:15000`, installs the existing debug APK, changes only the disposable emulator's saved `hubUrl`, `wanIp` and `apiKey`, and captures filtered SignalR logs. Its profile uses the **actual Android SignalR client** with real `/signalrhub`, `/terminalhub` and `/remoteterminalhub` requests; unlike `flight-link-run`, no protocol emulator substitutes for app code. Run the companion `flight-link-files-ui.cjs` to tap the actual Files screen and its read-only Refresh action (the APK installer/launcher itself does not tap UI). It requires a working, booted emulator and any first-run Android permission dialogs to be handled. Stop with `adb -s emulator-5554 shell am force-stop com.omni.sync`, `adb -s emulator-5554 reverse --remove tcp:15000`, and Ctrl+C for the proxy. The task's disposable AVD was stored in `E:/_AgentScratch/Tasks/Omni/OMN-0085/Temporary/avd`; nothing was installed on the user's phone.

**Automated real-APK Files UI check:** `flight-link-files-ui.cjs` refuses physical phones; it uses the Android UI hierarchy to open Files, tap the actual Refresh control under clean and slow800, force a 10-second outage during a further refresh, then retest after returning to clean. It records only UI-visible drive letters and generic error/empty-state labels (plus installed APK version) with timestamps, not folder contents or API keys. It verifies the original drive identity after a **manual** refresh; visible drives without a refresh after an outage may simply be **cached**, not authenticated state convergence. Pair its timestamps with `flight-link-report.cjs`'s per-command Hub receipt ledger. The first-run emulator permission dialogs must be handled before executing it; no user-device settings are altered. The script restores proxy profile `clean` even if the UI flow fails.

For a **manually chosen** physical-phone LAN test, bind `--listen` to the PC's specific LAN address (not `0.0.0.0`), point the app's Hub URL at `http://<PC LAN IP>:15000/signalrhub`, and restore the phone's normal URL afterward. **Older installed APKs** can rewrite manually configured endpoints during reconnect; source fixes in `28a98bf3` and `a76f1709` now preserve non-legacy endpoints and explicit WAN ports, but phone installation must be confirmed separately. The default harness must not expose port 15000 to the public internet or treat this proxy as an authentication layer; a separately approved and documented temporary WAN test is described below.

**Current overlay observation:** read-only `tailscale status --json` reported local backend `Running` with 23 funnel ingress nodes but no Android peer. It therefore establishes **neither a direct UDP path nor a DERP relay path to this phone**. When the Android device is online, inspect `tailscale status --json` and `tailscale ping <device>` or supported `tailscale netcheck` from the endpoint without changing configuration; record direct/relay transitions alongside these app traces.

### Repeatable direct/relay route evidence (read-only)

```powershell
# No Tailscale peer is pinged by default, and no settings/routes are changed.
node TestScripts/FlightLink/flight-link-transport.cjs --out E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/transport-<date>.json

# Only after Android appears in THIS host's tailnet device list:
node TestScripts/FlightLink/flight-link-transport.cjs --peer <phone-tailnet-hostname-or-IP> --out E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/transport-phone-<date>.json
```

This captures a redacted host `tailscale status --json` summary, `tailscale netcheck --format=json` capability, and—only when explicitly selected—up to five Tailscale pings classified as **direct**, **DERP**, or **unclassified**. It refuses nonexistent/ambiguous peers and evidence overwrite; the JSON contains no full peer inventory, authentication material or application content. **DERP observed from Windows does not prove the Android app can connect or that the aircraft permits Tailscale/HTTPS**. Pair the route snapshot with the Android app's FlightLink command ledger, capturing before/during/after any actual network change. Local `UDP: true` is only a statement about this home PC.

**HTTPS-only simulation**: The proxy's TLS listener accepts HTTPS/WSS and forwards plaintext to the **real local Rust Hub**; port 15000 has no plaintext listener in this mode. This verifies the application protocol over TLS, **not** actual aircraft firewall rules or Android certificate trust. Create a temporary localhost CA and cert in task scratch (Python `cryptography`), then exercise actual negotiate + WebSocket + Authenticate + GetAvailableDrives under all existing profiles:

```powershell
$cert = 'E:/_AgentScratch/Tasks/Omni/OMN-0085/Temporary/flight-tls'
python TestScripts/FlightLink/make-test-cert.py --out $cert
$env:NODE_EXTRA_CA_CERTS = "$cert/ca.pem" # this shell's Node processes only; no global trust modification
node TestScripts/FlightLink/flight-link-run.cjs --https-only --tls-cert "$cert/server.pem" --tls-key "$cert/server-key.pem" --out E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/flight-https
Remove-Item Env:NODE_EXTRA_CA_CERTS
```

Use a new scratch/cert and evidence path for reruns (the scripts refuse to overwrite existing certs/evidence). `--https-only` does not disable other TCP routes on Windows: it makes the **test proxy listener TLS-only**. The Android app still needs a user-trusted certificate for its actual test hostname and `https://<test host>:15000/signalrhub`; an Android HTTPS/WSS run remains **unverified**. A real aircraft firewall, DNS interception, Chinese blocking, or Tailscale DERP negotiation is **not** emulated by TCP `noUdp` or TLS-only profiles.

### Paired test with the user's existing Android phone (opt-in, no APK installation)

Use this only while the user is present and has agreed to temporarily change the **Omni app's Hub URL**. The headless and emulator paths above are self-contained; this path verifies the actual phone's radio, app connection handling and (optionally) Tailscale transport. **Record the original Hub URL before changing it and restore it immediately afterward.** An older APK may still overwrite the test URL during reconnect; the source fix is not proof the phone has installed it. Such a rewrite is a test result, not a reason to reconfigure the home Hub.

On the PC, choose one **already reachable private adapter** (the PC's Tailscale IPv4 for a tailnet test, or its LAN IPv4 for a same-Wi-Fi test). Do not bind to `0.0.0.0`, add a public port-forward or change Windows firewall/Tailscale settings. For Tailscale, read the current IPv4 with `tailscale ip -4`. In a PC PowerShell shell:

```powershell
$ip = (tailscale ip -4).Trim() # for LAN tests substitute the PC's existing LAN IPv4
$trace = 'E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/paired-phone-proxy.jsonl'
node TestScripts/FlightLink/flight-link-proxy.cjs --listen $ip --profile clean --trace $trace
```

The proxy control port remains **127.0.0.1:15001**. It will not alter production network settings. If port 15000 is blocked/unreachable, record the connection failure; don't change the firewall as part of this task. On the phone, connect via the already configured Tailscale/LAN link and temporarily set its Omni Hub URL to `http://<selected-private-ip>:15000/signalrhub`. Use the normal Android UI to open **Files → refresh drives** (a read-only command), noting when the app shows connected, when the list actually appears, and any stale state. The proxy logs request names, timings and Hub completions without message arguments.

While both sides watch the app and PC trace, progress through **clean → slow300 → slow800 → slow2000**, then a forced route switch, 10-second outage and (if tolerable) 120-second outage. Use a second PC shell:

```powershell
Invoke-RestMethod http://127.0.0.1:15001/profile -Method Post -ContentType application/json -Body '{"profile":"slow800"}'
Invoke-RestMethod http://127.0.0.1:15001/switch  -Method Post -ContentType application/json -Body '{"profile":"slow300"}'
Invoke-RestMethod http://127.0.0.1:15001/outage  -Method Post -ContentType application/json -Body '{"seconds":10}'
# Repeat with {"seconds":120} only when explicitly desired.
```

Repeat the selected profiles while changing **phone Wi-Fi → mobile data → Wi-Fi**, if the phone's private/Tailscale route remains viable. Record the observed Android reconnect time and verify drive IDs/state **after** reconnect, not just the "connected" badge. For actual Tailscale reachability, run read-only `tailscale status`, `tailscale ping <phone-tailnet-name>` and `tailscale netcheck`, recording direct vs DERP only when genuinely observed. The `noUdp` proxy profile never proves DERP; a HTTPS-only flight path still needs a trusted TLS certificate and separate HTTPS/WSS setup.

End the session by restoring the original phone Hub URL, stopping the proxy with Ctrl+C, and generating the evidence report:

```powershell
node TestScripts/FlightLink/flight-link-report.cjs --trace E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/paired-phone-proxy.jsonl --out E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/paired-phone-report
```

Log **each phone action**, whether it displayed success/failure, whether the Hub returned a completion, the recovery time and any unknown-delivery status. A missing completion is **not proof the Hub never received the command**. Never retry a potentially side-effecting phone action merely to find out; start with the read-only Files refresh.

### Temporary public-IP physical-phone session (19 November 2026)

The user chose **mobile data and no phone Tailscale**. With their explicit approval, port 15000 was temporarily forwarded by the home Zyxel router via NAT-PMP (one-hour lease) to 192.168.1.37:15000; Windows Firewall has an explicit temporary inbound TCP allowance for 15000 on that specific local IP and the active Public Ethernet profile. The proxy binds **only** 192.168.1.37:15000, forwarding to Rust Hub 127.0.0.1:5000; control remains loopback-only at 127.0.0.1:15001. **The existing public 5000 Hub remains unchanged.** Use `http://<current-public-IP>:15000/signalrhub` in the real Android app while testing; restore its working `http://<current-public-IP>:5000/signalrhub` afterward.

The temporary port is public **plaintext HTTP** with the same Hub API-key authentication as port 5000. Do not send sensitive commands/data when its transport is untrusted; it does not add encryption or authentication and should not be left open after testing. The NAT-PMP lease automatically expires if not renewed. To release it early, send a NAT-PMP TCP mapping request with internal/external port 15000 and lifetime zero to gateway 192.168.1.1, remove the specifically named Windows rule `OMN-0085 FlightLink temporary TCP 15000`, and stop the proxy. Do not delete the existing Omni rule or change 5000. Read the current profile from localhost `/status` before intentionally disturbing the phone. For actual flight-grade intermittent HTTP over the public internet, this reproduces the application's behavior under shaped TCP; **real in-flight captive-portal filters, DNS blocking, TLS-only enforcement or IP censorship are not simulated**.

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

The separate `flight-extended-v2` run includes the full **120-second outage**. Its read-only driver recovered on its first reconnect attempt **0.57 seconds after the simulated link-up**, with stable drive identity; this is **driver recovery**, not Android-background reconnect evidence. Across that extended run: 24 invocations, 22 Hub confirmations, 3 client-observed failures, 2 definitively not sent, 1 completion lost after Hub execution, and 3/3 driver state convergence checks. The 120-second disconnected attempt failed with `ws_closed_1006`; the runner cannot conclude Hub admission from that error.

**Actual Android debug APK in a disposable x86-64 emulator** made real requests through the proxy including `/signalrhub/negotiate`, `/signalrhub`, `/terminalhub` and `/remoteterminalhub`. Crucially, Android's Java SignalR sends **JSON records inside binary WebSocket frames (opcode 2)**, now captured by the proxy. In `android-final-proxy.jsonl` and `android-final/android-delivery-ledger.jsonl`, a slow300/1%-reset run captured **10 real Android app startup invocations** (Authenticate, GetOmniAiMode, SendBrowserCommand, AdvertiseAssistantCommands, GetReminders, GetTaskResurfacingTimes): **5 confirmed Hub completions**, **2 interrupted awaiting completion**, **3 fire-and-forget without receipts**. The interrupted calls' Hub delivery remains unknown; the proxy intentionally reset a main SignalR socket. Subsequent Android logcat showed a **10-second failed connection to synthesized 10.0.2.37:5000** followed by repeated failed WAN fallback. This is a confirmed actual APK reconnect/fallback failure, not merely the Node protocol driver. The APK path was **background startup/read-only observations, not an automated tap of the phone Files UI**; a physical phone, flight path and Tailscale DERP behavior remain unverified.

### 19 November 2026: physical phone over public WAN, and isolated HTTPS/WSS run

- The **actual Android phone** successfully connected over **mobile data** to the existing public Rust Hub HTTP:5000. A temporary, user-approved 15000 NAT-PMP lease and dedicated firewall rule routed it through `flightChaos`. The proxy observed real Android authenticated SignalR, File `ListDirectory` calls and Hub completions while conditions cycled 352 ms/3% → 1448 ms/7% → 2451 ms/13% → 3552 ms/44% → 10-second complete blackout → recovery. Seven folder-list invocations were observed; six had definitive Hub completions, one lost the client connection before a completion was observed (delivery **unknown**, not definitely failed). The dedicated firewall rule and router lease were removed afterward. The normal Rust Hub remained unchanged.
- **Forensic per-command ledger (actual phone, public WAN, synthetic flightChaos):** Running `flight-link-report.cjs` over `paired-phone-WAN-flight-chaos-20261119.jsonl` found **297 observed SignalR invocations: 264 definite Hub completions (including 2 Hub-reported errors), 10 without completion after interrupted sockets, and 23 fire-and-forget invocations without completion IDs**. Of the 10 interrupted calls, 4 ended with a simulated blackout reset, and 6 with a client-side socket close; **none** establishes that the Hub failed to execute. A total of **18 confirmed completions took over 10 seconds** from the proxy's client-side observation to its Hub-side completion. At command observation, 177 occurred in moderate, 57 in degraded, 48 in severe, and 15 in blackout phases; these are **not independent samples or airline statistics**. Evidence: `E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/phone-flight-chaos-ledger-v2/` (redacted ledger/summary), reproducible with the report command above. This is a real Android→WAN→shaper→live Hub run but does **not** prove when the UI displayed a result.
- **Network/URL confounder**: phone Tailscale-IP:5000 and :15000 were unreachable (no phone tailnet peer observed), so *neither direct Tailscale nor DERP can be claimed tested*. Back on home Wi-Fi, public-IP:5000 `/signalrhub/negotiate` returned HTTP 400 from the OpenWrt router, while localhost Hub negotiate returned HTTP 200: LAN hairpin to public WAN IP is not a valid proxy for remote flight connectivity. Phone Logcat's retained window began **after** the main flight stress test, so it cannot prove the exact earlier Android UI errors. See task OMN-0138 for remote app-owned diagnostics, distinct from Hub logs.
- **Isolated TLS-only run** (live Rust Hub, 19 Nov): 21 real SignalR invocations over **HTTPS negotiate + WSS** with temporary CA (20 Hub confirmations, one unknown during forced outage, zero misattributed non-deliveries); **2/2 state convergence**, 3.47 s recovery after route switch and 0.53 s after 10-second outage. The `slow2000` TLS/WSS negotiate, handshake and auth needed **9.15 s**, dangerously near the 10-second Android startup timeout. Evidence: `E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/https-only-20261119/` (commands, proxy trace, per-command ledger, summary). TLS was verified on the isolated Node driver, **not** on Android or an actual Tailscale/airline route.

### 20 November 2026: repeatable Tailscale path snapshot

Running `flight-link-transport.cjs` against the host, without a target ping, recorded backend `Running`, **0 non-ingress tailnet devices**, 23 funnel-ingress nodes, host UDP available, and no observed Android direct or DERP path. The redacted result is `E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/transport-snapshot-20261120.json`. This does **not** establish whether the phone can join the tailnet or connect via DERP. A selected physical-phone route probe still requires that device to be online and present in this same tailnet.

A fresh independent **live Rust Hub** loopback rerun on 20 Nov (`E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/refresh-20261120/`) recorded **19 read-only invocations, 18 Hub-confirmed completions, 2 client failures, 1 completion lost after reaching the Hub, and 1 delivery-unknown command**. The `slow2000` GetAvailableDrives failed with WebSocket close 1006 after 7.69 s; a command during the 10-second outage also closed with 1006. Both route-change and outage recoveries converged to the original six-drive identity (3.90 s after route change; 0.60 s after simulated link-up). This is protocol-driver evidence, not a new physical-phone result. The emulator/physical phone was not connected through ADB during the rerun; the disposable proxy exited and no test listener remains on 15000/15001. The real phone/Tailscale transport needs in-session user collaboration before acceptance.

### 20 November 2026, afternoon: current repeat run and physical-phone handoff

- At ~14:23 CET, re-ran the isolated, read-only, real-Rust-Hub harness into `E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/afternoon-20261120-1423/`. The ledger records **18 invocations**, **17 Hub-confirmed completions**, **2 client-observed failures**, including **one Authenticate completed on the Hub but lost to the client** under slow2000 (WS 1006) and **one GetAvailableDrives delivery unknown** during outage10 (WS 1006). Both route-change and outage recoveries converged (3.87s and 0.70s after link-up). These are protocol-driver observations, not a fresh physical-phone test.
- The earlier **physical-phone public-WAN** flight-chaos run remains the real Android-to-Hub evidence. The 20-Nov phone OTA attempt reached `installer_started` via Hub's PackageInstaller bridge, but **successful installation was not observed** (no ADB device; no final installed receipt). The temporary public test proxy/lease was not active at the afternoon check. The production Hub responded to negotiate on loopback, and the Tailscale host view showed no phone peer. Do not assert current public `:15000` or Tailscale reachability without new measurements.
- The 20-Nov APK download through the disposable `clean` proxy originally failed because the proxy overwhelmed its 2 MB write queue. Commit `283bf30f` added TCP backpressure; the subsequent 57,969,857-byte APK transfer matched the origin SHA-256. This establishes proxy download integrity, **not phone install success**. For the next collaborative phone pass, verify the installed APK/version, connect through a reachable test URL, exercise Files refresh with `/status` and the ledger recording, then restore the normal URL and remove only the temporary test exposure.

### 20 November 2026, evening: automated real Android Files refresh

- Booted the disposable Android 36.1 emulator and installed real OmniSync debug APK `dev.1795190019`, connecting to the **live Rust Hub** through the existing isolated localhost FlightLink proxy. The actual Files tab was opened and Refresh tapped, not merely app startup. During slow800 (synthetic 800 ms RTT, ±200 ms jitter, 32 kb/s, approximate 3% reset chance), the Files UI first showed **“Not connected and root not cached”**, then **“No files found”** after a refresh, despite Dashboard earlier saying Connected. After switching to clean and refreshing, five root drives became visible. This establishes transient confusing UI feedback and recoverability, but does not prove which individual RPC caused the empty-state transition.
- Added `flight-link-files-ui.cjs` to repeat real APK Files UI refresh through **clean → slow800 → 10-second outage → clean**. New evidence `E:/_AgentScratch/Tasks/Omni/OMN-0085/Evidence/evening-android-files-ui-20261120.json`: the initial clean drive set remained visible in all five UI snapshots, including during simulated disconnection; these may be **cached** and do **not** prove backend state convergence. A clean manual refresh showed the same five drives. The full APK proxy ledger at `Evidence/evening-emulator-final-report-20261120/` observed **148 real app invocations**, **99 Hub-confirmed completions**, **20 socket resets**, and **7 GetAvailableDrives calls** (**4** definite completions, **3** interrupted/unknown). There were 11 confirmed completions taking over 10 seconds. Unknown receipts are not failed Hub commands. This run was app/emulator-over-local-proxy, **not** physical-phone/Tailscale or aircraft Wi-Fi.
- Emulator and proxy were stopped after the run; 15000/15001 test listeners are absent. No production route, Hub setting, router port mapping, firewall rule, or physical phone configuration was changed.

## Prioritized engineering fixes

1. **P0 — Preserve configured Hub URLs (source mitigation implemented; phone unverified).** The original Android endpoint resolver replaced the explicitly configured test origin after a link change and fell back to an unreachable LAN host. Commits `28a98bf3` and `a76f1709` now distinguish legacy LAN from pinned/Tailscale/HTTPS origins, parse WAN host:port or full URLs, retain a manually chosen WAN port across discovery and display a preview in Settings. The earlier emulator failure remains valid for old APKs; validate the fix **on the actual installed phone build** before marking it resolved.
2. **P0 — Give commands stable IDs + durable statuses at both ends.** Many Android `hubConnection.send(...)` calls are fire-and-forget. A timeout/WS close means **delivery unknown**, not safely retryable. Persist command ID and Hub admission/completion receipts; for side effects use idempotency/dedup at Hub, and surface Sent → Hub received → Completed/Failed/Unknown to user.
3. **P1 — Mobile network change aware reconnect.** Prefer last successful endpoint, react to Android ConnectivityManager changes immediately, abort dead URL negotiation, reset old sockets, cap 10s × N sequential fallback stalls. Backoff up to 60s adds cost after a temporary outage; record *link restored → authenticated → state reloaded* separately.
4. **P1 — Check and tune timeouts by operation.** Android startup has a 10s timeout; real slow2000 TCP path authenticated in ~8.81s before a randomly induced disconnect. Avoid treating “connected” as “authenticated and state converged”; e.g., the app calls `authenticateClient()` asynchronously after `setConnected(true)`. Make timeouts context-sensitive and propagate explicit failure.
5. **P1 — Verify HTTPS-only/DERP in a separate endpoint-instrumented pass.** Run Android with trusted HTTPS front-end and WSS/LongPolling, then separately compare Tailscale direct UDP, DERP and blocked UDP using `tailscale status`/metrics (read-only). Captive portal and Chinese DNS/HTTPS filtering cannot be inferred from a localhost shaper.
6. **P1 — Do not render a failed Files refresh as an empty directory.** Under degraded emulator transport, Files displayed both a disconnected/no-cache warning and subsequently “No files found,” even though a later clean refresh revealed five drives. Distinguish command timeout/unknown delivery from an authoritative empty result; retain the last known drive list as **stale**, not current, until a confirmed refresh completes.
7. **P2 — Reconcile state after reconnect.** Refresh the affected screen's snapshots, compare stable entity IDs/revisions, and surface stale data rather than silently claiming resumed. The UI runner records visible drive identity; cached values during blackout are not proof of backend revalidation, and other UI state remains unverified.

These initial isolated measurements made no production network or phone configuration changes. The later user-approved live WAN test temporarily changed only a dedicated TCP 15000 firewall rule and NAT-PMP lease, and the user temporarily selected the phone's test URL. No airline connectivity claims are implied and the read-only reproduction runner sends no destructive RPCs.
