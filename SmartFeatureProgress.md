# Smart Home Integration Progress

This document tracks the status of the "Hybrid" Smart Home integration for OmniSync, using Node-RED as a bridge between Google Home and the Omni Hub.

## 1. Architectural Overview
The system uses a multi-layered approach to bypass complex OAuth2 requirements while maintaining local control:
1.  **Google Home**: Receives voice/app commands from the user.
2.  **Google Home Bridge**: A cloud-to-local relay (hardill.me.uk).
3.  **Node-RED**: Runs on the Windows PC, receives events from the bridge, and translates them into Hub API calls.
4.  **Omni Hub**: Exposes a REST API (`ExternalCommandController`) to receive commands from Node-RED.

---

## 2. Implementation Status

| Phase | Description | Status | Details |
| :--- | :--- | :--- | :--- |
| **Phase 1** | Non-Programming Setup | **User Action Needed** | Requires setting up bridge account and installing Node-RED. |
| **Phase 2** | Hub API Implementation | **DONE** | `ExternalCommandController` added to Hub. Supports authenticated POST requests. |
| **Phase 3** | Node-RED Configuration | **User Action Needed** | User must configure the flows in the Node-RED UI. |
| **Phase 4** | Command Registry | **STABLE** | Leverages existing `CommandDispatcher`. All Hub commands are available via API. |
| **Phase 5** | Hub -> Node-RED (Reverse) | **PLANNED** | Stretch goal: Hub sending requests to Node-RED to control other devices. |

---

## 3. Technical Details

### Hub REST API
The `ExternalCommandController` is now live at:
`POST http://localhost:5000/api/external/command?key={API_KEY}&cmd={COMMAND}`

It accepts a JSON body for the payload. 
Example for setting volume:
- **URL**: `.../command?key=test_api_key&cmd=SET_VOLUME`
- **Body**: `{"VolumePercentage": 50}`

### Integration Benefits
- **Zero-Latency**: Localhost communication between Node-RED and Hub.
- **High Security**: Uses the same API Key logic as SignalR.
- **Extensibility**: Any command added to `CommandDispatcher` is automatically available to Google Home.

---

## 4. Missing / Next Steps

1.  **Phase 5 (Outbound Control)**: **DONE** - `NodeRedService` implemented in Hub to send POST requests to Node-RED. `TRIGGER_NODERED` command added.
2.  **Discovery API**: **DONE** - `GET /api/external/commands` endpoint added to `ExternalCommandController`.
3.  **UI Feedback**: **DONE** - `TrayIconManager` now shows balloon notifications for external commands via `HubMonitorService` events.
