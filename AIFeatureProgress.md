# OmniSync AI Feature Progress

This document tracks the implementation, architecture, and status of AI-related features within the OmniSync ecosystem.

Always use cleanup_gemini_windows.py between test runs, or even better, adjust all scripts to call cleanup_gemini_windows.py as the first thing. Careful not to kill yourself as YOU are a cli gemini window but the cleanup script protects you.
NEVER run commands like "Shell taskkill /IM node.exe /F [current working directory D:\SSDProjects\Omni]   "
as that will kill yourself and prevent you from fufilling any tasks.

## Architectural Overview

The AI system has transitioned from a separate Python listener to a **Direct C# Hub Integration**.

1.  **Mobile Client (Android)**: Sends prompts via SignalR to the Hub.
2.  **Omni Hub (C#)**: Directly manages named pipe connections to Gemini instances via `AiCliService`.
3.  **Gemini CLI (Node/React)**: Listens for `RemotePrompt` events via named pipe and responds.
4.  **Response Relay**: The Hub's `AiCliService` captures responses and broadcasts them via SignalR.

---

The project uses two workspaces
D:\SSDProjects\Omni, the main omni project with the hub, test scripts and android app
D:\SSDProjects\Tools\gemini-cli a custom/forked version/repo of gemini CLI with custom hooks into the CLI to be used by Omni

## Component Deep-Dive

### 1. AI Gateway (`OmniSync.Hub`)

#### `AiCliService.cs` (C#)
Replaces the legacy `ai_listener.py`.
- **PID Discovery**: Uses WMI (`System.Management`) to find node processes running `bundle/gemini.js` or `dist/index.js`.
- **Named Pipe IPC**: Maintains a dictionary of `GeminiSession` objects, each managing an asynchronous `NamedPipeClientStream`.
- **Streaming Responses**: Implements an async read loop for each pipe to capture real-time responses.
- **Multi-Session**: Supports discovering, switching between, and targeting specific Gemini instances by PID.

#### `gemini-cli` Customizations
- **`remoteControl.ts`**: Implements the IPC server. Supports `prompt` and `getHistory` commands.
- **`useGeminiStream.ts`**: Modified to emit `RemoteResponse` both after model turns and specifically when slash commands are handled.
- **`AppContainer.tsx`**: Listens for `RequestRemoteHistory` and serializes the React history state for transport over the pipe.

---

### 2. Testing & Validation (`TestScripts/AIFeature`)

The test suite has been expanded to cover advanced scenarios:
- `test_hub_mediated_roundtrip.py`: Verified Hub-integrated listener flow.
- `test_hub_mediated_multi_cli.py`: Validated multi-session discovery and targeted communication from the Hub.

---

## Current Status & Known Issues

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **SignalR AI Relay** | Stable | Verified with automated tests. |
| **C# Hub Integration** | Stable | **NEW**: AI Listener is now a built-in service in OmniSync.Hub. |
| **Named Pipe IPC** | Stable | High performance, no focus-stealing issues. |
| **Slash Command Injection**| Stable | Now fully programmatic via Named Pipe (no pyautogui). Feedback captured. |
| **Multi-Session Support** | Stable | Full lifecycle (Start, List, Switch) integrated into Hub and Android UI. |
| **History Synchronization** | Stable | Conversations are synced when switching sessions or starting new ones. |
| **Process Cleanup Safety**| Stable | Cleanup scripts now protect ancestors and windows with "Omni" in title to avoid self-termination. |
| **Android AI Chat** | Stable | Multi-session UI, /clear command, and auto-sync history. |

### Resolved: Multi-Session Management & History Synchronization
1.  **Session Discovery**: AI Listener now scans for all active Gemini processes and reports their PIDs back to the Hub.
2.  **On-Demand Launch**: Hub startup no longer auto-launches Gemini CLI. The AI Listener now triggers `launch_gemini_cli.py` on-demand.
3.  **IPC History Export**: Added `getHistory` command to the Named Pipe IPC. `AppContainer.tsx` serializes the current `historyManager` state, wrapped in `[HISTORY_START]` and `[HISTORY_END]` markers.
4.  **Session Switching**: Android UI now features a dropdown to switch between active sessions. Switching triggers a history sync.
5.  **SignalR Core Extensions**: Added session management methods to `RpcApiHub.cs` and `SignalRClient.kt`.

---

## Ultimate goal
The ability to create, List, switch-between, close and interact with multiple CLI windows on the PC from the Android app through the hub as the middleman.
We are achieving this by first establishing full control over gemini cli, then integrating the control into the hub and finally android to give us the full CLI <--> Hub <--> Android. 

## Next Steps