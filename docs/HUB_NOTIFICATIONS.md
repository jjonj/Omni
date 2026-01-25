# Hub Tray Notifications

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
