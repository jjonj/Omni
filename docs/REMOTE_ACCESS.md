# Remote Hub access and Wake-on-LAN

This documents the direct internet connection configured on 7 October 2026. Android connects to the Hub through a TCP port forward and wakes the PC through a separate UDP port forward. Tailscale is not required for this setup. No dynamic DNS account is configured; the Hub distributes its latest public IPv4 address to the phone instead.

## Installed configuration

| Item | Value |
| --- | --- |
| Router | Zyxel EMG5523-T50B |
| Router firmware observed during setup | `V5.50(ABPM.9.8)C0` |
| Router administration, from home LAN | `http://192.168.1.1` |
| Active internet interface | `ETHWAN` |
| LAN subnet | `192.168.1.0/24` |
| Windows PC | `DESKTOP2024` |
| Reserved PC IPv4 address | `192.168.1.37` |
| PC Ethernet MAC address | `10:FF:E0:37:9D:AC` |
| Public IPv4 observed on 7 October 2026 | `89.150.166.124` (can change) |
| Local Hub URL | `http://192.168.1.37:5000/signalrhub` |
| Remote Hub URL at setup time | `http://89.150.166.124:5000/signalrhub` |

Router credentials are deliberately excluded from this document.

## Router settings

### PC address reservation

Open **Network Setting > Home Networking > Static DHCP**. The add control is labelled **Static DHCP Configuration** and has a plus icon.

An active reservation was saved with:

- Group: `Default`
- IP type: `IPv4`
- Device: `DESKTOP2024`, or enter its Ethernet MAC manually
- MAC: `10:FF:E0:37:9D:AC`
- IP: `192.168.1.37`

This keeps both port forwards pointing at the same PC. The router displays a reconnect/release-renew notice when saving a reservation. The PC already had this address, so it was not disconnected during setup.

### Port forwarding

Open **Network Setting > NAT > Port Forwarding > Add New Rule**.

| Setting | Hub connection | Wake-on-LAN |
| --- | --- | --- |
| Active | Enabled | Enabled |
| Service name | `Omni Hub` | `Omni WOL` |
| WAN interface | `ETHWAN` | `ETHWAN` |
| Start port | `5000` | `9` |
| End port | `5000` | `9` |
| Translation start port | `5000` | `9` |
| Translation end port | `5000` | `9` |
| Server IP address | `192.168.1.37` | `192.168.1.37` |
| Configure originating IP | Disabled / unrestricted | Disabled / unrestricted |
| Protocol | `TCP` | `UDP` |

Both rules were saved and read back as enabled. These are individual port rules, not a DMZ configuration.

The router also has **Home Networking > Wake on LAN**, with a manual **Wake Up** button. That page makes the router send a local wake packet; it is separate from the incoming UDP forwarding rule used by Omni.

## Windows and Android settings

The Hub listens on port `5000` on all interfaces. It must be running after the PC boots. Windows Firewall was disabled for all profiles when this setup was verified; no firewall rules were changed. If Windows Firewall is enabled later, allow inbound TCP `5000` for the Hub on the relevant profile.

The PC uses its wired Realtek Ethernet adapter. At setup time these adapter options were enabled:

- **Wake on Magic Packet**
- **Shutdown Wake-On-Lan**
- **Wake on pattern match**

The first two are relevant to waking through Omni. Pattern matching was already enabled and was not changed. Firmware and adapter power support still determine whether waking from full shutdown works.

In Android's settings use:

| Field | Value |
| --- | --- |
| Hub URL (Local) | `http://192.168.1.37:5000/signalrhub` |
| WAN IP (Public, auto) | Latest public address supplied by the Hub |
| Wake-on-LAN MAC Address | `10FFE0379DAC` |
| Subnet Broadcast IP | `192.168.1.255` |
| API key | Must match the Hub's configured key |

The checked-in initial WAN default is an older address, `85.80.233.70`. Treat it as a default, not the current router address. A connected app saves the value supplied by the Hub.

## How the phone connects

Android constructs the remote URL by replacing the hostname in its local Hub URL with `wanIp`, keeping the scheme, port and path.

- On Wi-Fi, it tries the local URL first, then the WAN URL.
- On mobile data, it tries the WAN URL first, then the local URL.
- Failed connections enter the existing automatic reconnect process.

Wi-Fi detection does not identify the home network specifically. On another Wi-Fi network the local attempt can fail before the remote attempt succeeds. The reconnect code can also infer a local Hub address ending in `.37` from the phone's private subnet; the saved WAN address remains the remote candidate.

The remote path is:

```text
Phone on mobile data
  -> public IPv4, TCP 5000
  -> router's Omni Hub forward
  -> 192.168.1.37:5000/signalrhub
  -> SignalR connection and API-key authentication
```

The connection uses HTTP, without TLS. The user chose direct forwarding with security as a low priority. Port `5000` exposes more than the authenticated SignalR endpoint: the current Hub also serves unauthenticated file-download and screenshot endpoints. This is part of the deployed configuration, not an assertion that every endpoint requires the API key.

## Keeping the public IP current

`ExternalIpService` already provides updates more frequently than daily:

1. Load the last saved address and check timestamp at Hub startup.
2. Wait approximately 20 seconds, then resolve the public IPv4 address.
3. Repeat resolution every six hours while the Hub runs.
4. Prefer the router's UPnP external address; fall back to an HTTPS request to `https://api.ipify.org`.
5. Save the resolved address and UTC timestamp in Hub settings.
6. Broadcast `UpdateExternalIp(address, checkedAtUnix)` to connected clients after each successful refresh, even if the address is unchanged.

The Hub also sends the cached address to each newly connected client. Android handles `UpdateExternalIp`, validates the address, updates `wanIp` when it changes, and persists its app configuration. That saved address is used for both remote connections and remote WOL. Failed Hub lookups retain the last known address.

`GetHubStatus` exposes `ExternalIp`, `ExternalIpSource`, and `ExternalIpUpdatedAtUnix`. The authenticated `RefreshExternalIp` method triggers an immediate resolution and broadcast.

**Disconnected-phone limitation:** these updates travel over the Hub connection. They are not delivered by SMS, push notification, or an external discovery service. If the public IP changes while the phone is disconnected, the phone may be unable to connect using its old address. Reconnect on the home LAN to receive the update, or manually enter the new WAN address. The same issue affects remote WOL if the address changes while the PC is off. Dynamic DNS could address this separately, but was not configured.

## How remote wake works

Android builds a standard magic packet containing the PC's Ethernet MAC. It attempts UDP sends on port `9` to:

1. The configured subnet broadcast address, `192.168.1.255`.
2. The global broadcast address, `255.255.255.255`.
3. The saved public WAN address.

The first two are local broadcast attempts. Outside the home LAN, the WAN send is the relevant one:

```text
Phone -> public IPv4, UDP 9
      -> router's Omni WOL forward
      -> PC Ethernet adapter at 192.168.1.37
      -> PC powers on; Hub starts; phone reconnects
```

The Hub does not need to be running to receive the wake packet. Android starts attempting a Hub connection after a successful packet send and schedules a connection check approximately three minutes later. A successful UDP send means the phone handed the packet to the network; it does not by itself confirm that the PC woke.

**Long-shutdown limitation:** the router rejected a forwarding target of `192.168.1.255` with “Invalid IP address”, so the installed rule targets the PC's unicast IP. Delivering that packet can depend on the router retaining an IP-to-MAC/ARP mapping while the PC is off. A DHCP reservation does not establish that a permanent ARP entry exists. No permanent ARP entry was configured. Remote WOL was confirmed by the user, but reliability after an overnight shutdown or router restart has not been established.

## Verification and troubleshooting

Verified on 7 October 2026:

- User confirmed remote WOL worked.
- At 11:19 local time, Hub logs recorded an Android SignalR connection from a public mobile-network address and successful authentication.
- `python TestScripts/System/test_external_ip.py` passed against the running Hub: cached address on connection, status metadata, immediate refresh, and refresh broadcast.
- No Hub or Android code changes or app update were required for this setup.

To check remote access, turn off the phone's Wi-Fi and connect in Omni. Look in `hub_log.txt` for `Client connecting` and `authenticated successfully`. A public source address confirms the phone reached the forwarded Hub rather than using the home LAN. A test from the PC to its own public IP can depend on NAT loopback and is not a substitute for this mobile-data test.

| Symptom | Checks |
| --- | --- |
| No connection reaches Hub logs | Phone's saved WAN IP, enabled TCP forwarding rule, Hub running/listening, Windows Firewall |
| Connection arrives but authentication fails | Phone API key matches Hub configuration |
| Local access works but mobile access fails | WAN address, router's `ETHWAN` rule, ISP inbound restrictions or upstream NAT |
| WOL works locally but not remotely | Saved WAN IP, UDP `9` rule, Ethernet MAC, router's ARP mapping |
| WOL works shortly after shutdown but not overnight | Suspect expired ARP mapping; the current setup has no confirmed permanent mapping |
| Phone has a stale public IP | Connect on home Wi-Fi to learn the current value, or update WAN IP manually |

Run the public-IP integration check with the Hub already running:

```powershell
python TestScripts/System/test_external_ip.py
```

The script requires `signalrcore`; its configured API key must match the running Hub. It triggers a real public-IP refresh and broadcasts the result. It does not shut down the PC or test external UDP delivery.

## Implementation references

- [Hub public-IP service](../OmniSync.Hub/src/OmniSync.Hub/Logic/Services/ExternalIpService.cs)
- [Hub connection events and status/refresh methods](../OmniSync.Hub/src/OmniSync.Hub/Presentation/Hubs/RpcApiHub.cs)
- [Android connection target selection](../OmniSync.Android/app/src/main/java/com/omni/sync/data/repository/HubConnectionTargets.kt)
- [Android IP update handler and reconnect logic](../OmniSync.Android/app/src/main/java/com/omni/sync/data/repository/SignalRClient.kt)
- [Android magic-packet sender](../OmniSync.Android/app/src/main/java/com/omni/sync/logic/assistant/WakeOnLanSender.kt)
- [Android wake verification](../OmniSync.Android/app/src/main/java/com/omni/sync/receiver/AssistantCommandReceiver.kt)
- [Live public-IP test](../TestScripts/System/test_external_ip.py)
