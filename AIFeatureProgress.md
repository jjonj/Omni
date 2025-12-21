# OmniSync AI Feature Progress

This document tracks the implementation, architecture, and status of AI-related features within the OmniSync ecosystem.

Always use cleanup_gemini_windows.py between test runs, or even better, adjust all scripts to call cleanup_gemini_windows.py as the first thing. Careful not to kill yourself as YOU are a cli gemini window but the cleanup script protects you.
NEVER run commands like "Shell taskkill /IM node.exe /F [current working directory D:\SSDProjects\Omni]   "
as that will kill yourself and prevent you from fufilling any tasks.

## Architectural Overview

The AI system has transitioned from brittle UI automation to a robust **Programmatic Named Pipe Hook**.

1.  **Mobile Client (Android)**: Sends prompts via SignalR to the Hub.
2.  **Omni Hub (C#)**: Broadcasts prompts to all authenticated listeners.
3.  **AI Listener (Python)**: Discovers running Gemini instances, connects to their unique named pipes (\\.\pipe\gemini-cli-<pid>), and injects prompts.
4.  **Gemini CLI (Node/React)**: A custom `remoteControl.ts` server listens for `RemotePrompt` events, submits them to the model, and emits `RemoteResponse` events upon completion.
5.  **Response Relay**: The Python listener captures the response from the pipe and relays it back to the Hub.

---

The project uses two workspaces
D:\SSDProjects\Omni, the main omni project with the hub, test scripts and android app
D:\SSDProjects\Tools\gemini-cli a custom/forked version/repo of gemini CLI with custom hooks into the CLI to be used by Omni

## Component Deep-Dive

### 1. AI Gateway (`OmniSync.Cli`)

#### `ai_listener.py`
Refactored to support the Named Pipe architecture and session management.
- **PID Discovery**: Automatically finds all `node` processes running `bundle/gemini.js` or `dist/index.js`.
- **Async I/O**: Uses `asyncio.to_thread` to handle synchronous pipe communication without blocking the SignalR loop.
- **Auto-Launch**: Automatically invokes `launch_gemini_cli.py` if no active session is found when a message arrives.
- **History Relay**: Handles the `getHistory` IPC command to fetch and relay conversation history from the CLI to the Hub.

#### `gemini-cli` Customizations
- **`remoteControl.ts`**: Implements the IPC server. Supports `prompt` and `getHistory` commands.
- **`useGeminiStream.ts`**: Modified to emit `RemoteResponse` both after model turns and specifically when slash commands are handled.
- **`AppContainer.tsx`**: Listens for `RequestRemoteHistory` and serializes the React history state for transport over the pipe.

---

### 2. Testing & Validation (`TestScripts/AIFeature`)

The test suite has been expanded to cover advanced scenarios:
- `test_auto_multi_cli.py`: Automates launching multiple Gemini instances and verifies parallel communication with each via unique pipes.
- `full_stack_hub_mediated_multi_instance_test.py`: Validates the Hub's ability to coordinate multiple sessions and relay history.

---

## Current Status & Known Issues

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **SignalR AI Relay** | Stable | Verified with automated tests. |
| **Named Pipe IPC** | Stable | High performance, no focus-stealing issues. |
| **Slash Command Injection**| Stable | Now fully programmatic via Named Pipe (no pyautogui). Feedback captured. |
| **Multi-Session Support** | Stable | **ENHANCED**: Full lifecycle (Start, List, Switch) integrated into Android UI. |
| **History Synchronization** | Stable | Conversations are synced when switching sessions or starting new ones. |
| **Process Cleanup Safety**| Stable | Cleanup scripts now protect ancestors and windows with "Omni" in title to avoid self-termination. |
| **Android AI Chat** | Stable | **ENHANCED**: Multi-session UI, /clear command, and auto-sync history. |

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