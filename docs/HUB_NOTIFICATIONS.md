# Hub Tray Notifications

## Tailscale and remote-access outages

OmniHub has dedicated connectivity alerts, independent of the general tray
notifications (which are currently suppressed). `ConnectivityAlertService`
checks the independent MCP watchdog's status every five seconds. Two bad
samples produce a topmost red popup on a non-primary monitor, with a warning
sound. The same alert repeats with sound every 30 seconds until recovery.
It does not take keyboard focus or depend on Windows toast settings.

The alert covers Tailscale service/connection loss, required login or machine
authorization, failed public MCP routes, local Lite failure, and a missing or
stale watchdog heartbeat. Public checks run every minute; direct Tailscale
state checks run with the watchdog's five-second loop. An authorization
failure tells the user to open Tailscale and suppresses futile service restart
attempts. It never stores the one-time authorization URL in the status file.

The popup offers **Snooze 1 minute** and **Open Tailscale**. Closing it snoozes
instead of permanently dismissing an unresolved outage. Recovery overrides
snooze, shows a green confirmation, and closes it after eight seconds.
The watchdog's intentional `pause.txt` maintenance marker suspends alerts;
a stale heartbeat still raises an alert even if its last state was paused.

Implementation: `Logic/Monitoring/ConnectivityAlertService.cs`,
`ConnectivityAlertState.cs`, and `Presentation/ConnectivityAlertWindow.cs`.
`HubMonitorService.ConnectivityAlertChanged` carries the alert to the tray UI.
The independent watchdog publishes state and logs under
`%LOCALAPPDATA%\OmniSync\lite-watchdog`.

Validation: `ConnectivityAlertStateTests` covers confirmation, repetition,
recovery, maintenance, authorization, public-route failures and stale status.
`TestScripts/McpServer/test_connectivity_alert_live.ps1` simulates an outage
by temporarily replacing watchdog health, verifies the actual topmost popup
on a secondary monitor without stealing focus, checks close-to-snooze and
recovery auto-close, and restores the running watchdog in `finally`. It does
not disconnect Tailscale or stop Lite/Hub.

The OmniSync Hub can display Windows tray notifications (toasts) when certain events occur. This is useful for providing immediate feedback without requiring the Hub window to be open.

## How to Trigger a Notification

Tray notifications are managed by the `HubMonitorService`. To show a notification from anywhere in the Hub backend:

1.  Ensure you have access to `HubMonitorService` (usually injected via Dependency Injection).
2.  Call the `OnExternalCommandReceived(string message)` method.

### C# Example

```csharp
// Inside a service or hub
_monitorService.OnExternalCommandReceived("Chrome: Extension Reloaded");
```

## How It Works

1.  **HubMonitorService**: This service raises the `ExternalCommandReceived` event.
2.  **TrayIconManager**: This component (part of the WPF presentation layer) listens for the `ExternalCommandReceived` event on the `HubMonitorService`.
3.  **WPF Notification**: When the event is raised, `TrayIconManager` uses the WPF `Hardcodet.Wpf.TaskbarNotification` (if available) or standard Windows API to show a balloon tip or toast notification.

## Current Usages

-   **ExternalCommandController**: Notifications are automatically shown when a command is received via the HTTP API (`/api/command`).
-   **TFT Hotkeys**: Some hotkey actions can be configured to show notifications for confirmation.

## Customization

To add notifications to new actions, simply find the logic that executes the action (e.g., in `HubEventSender` or `RpcApiHub`) and add the `_monitorService.OnExternalCommandReceived("Your Message");` call.
