# OmniSync AI Feature Progress

This document tracks the implementation, architecture, and status of AI-related features within the OmniSync ecosystem.

Always use cleanup_gemini_windows.py between test runs, or even better, adjust all scripts to call cleanup_gemini_windows.py as the first thing. Careful not to kill yourself as YOU are a cli gemini window but the cleanup script protects you.
NEVER run commands like "Shell taskkill /IM node.exe /F [current working directory D:\SSDProjects\Omni]   "
as that will kill yourself and prevent you from fufilling any tasks.

When testing the AI, do not tell it vague things like e.g. "Debug Run 1" which it interprets to start working on stuff and it may try to call tools which it did not get permission for and therefore hang. Dont send vague messages like that to the AI, instead tell it to "Repeat the following: Debug run 1" or something like that that will cause it to act simply.


## Architectural Overview

The AI system has transitioned from a separate Python listener to a **Direct C# Hub Integration**.

1.  **Mobile Client (Android)**: Sends prompts via SignalR to the Hub.
2.  **Omni Hub (C#)**: Directly manages named pipe connections to Gemini instances via `AiCliService`.
3.  **Gemini CLI (Node/React)**: Listens for `RemotePrompt` events via named pipe and responds.
4.  **Response Relay**: The Hub's `AiCliService` captures responses and broadcasts them via SignalR.

---

The project uses two workspaces:
- `D:\SSDProjects\Omni`: The main OmniSync project (Hub, Test Scripts, Android App).
- `D:\SSDProjects\Tools\gemini-cli`: A custom/forked Gemini CLI with IPC hooks.

## Component Deep-Dive

### 1. AI Gateway (`OmniSync.Hub`)

#### `AiCliService.cs` (C#)
Replaces the legacy `ai_listener.py`.
- **Robust Discovery**: Uses WMI to find node processes. Includes filtering to ignore standard global `@google/gemini-cli` installs that lack the IPC bridge.
- **Parallel Connection**: Connection attempts to discovered PIDs are parallelized for high performance, especially when stale processes are present.
- **Named Pipe IPC**: Manages `GeminiSession` objects with asynchronous `NamedPipeClientStream`.
- **Streaming Responses**: Async read loop captures real-time response chunks, turn markers, and history data.
- **Auto-Launch**: Automatically triggers `launch_gemini_cli.py` if a prompt is sent but no active sessions are found.

#### `gemini-cli` Customizations
- **`remoteControl.ts`**: Implements the IPC server. Supports `prompt` and `getHistory` commands.
- **`useGeminiStream.ts`**: Modified to emit `RemoteResponse` both after model turns and specifically when slash commands are handled.
- **`AppContainer.tsx`**: Listens for `RequestRemoteHistory` and serializes the React history state for transport over the pipe.

---

### 2. Testing & Validation (`TestScripts/AIFeature`)

The test suite has been fully migrated to use the native Hub integration:
- `test_hub_mediated_roundtrip.py`: Verified Hub-integrated listener flow.
- `test_hub_mediated_multi_cli.py`: Validated multi-session discovery and targeted communication.
- `test_native_hub_auto_launch.py`: Verified the auto-launch and on-demand discovery flow.
- `full_stack_hub_mediated_roundtrip_test.py`: Full integration regression (Hub + CLI + SignalR).

---

## Current Status & Known Issues

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **SignalR AI Relay** | Stable | Verified with automated tests. |
| **C# Hub Integration** | Stable | **REPLACED**: AI Listener is now a high-performance built-in service in OmniSync.Hub. |
| **Named Pipe IPC** | Stable | High performance, no focus-stealing issues. |
| **Slash Command Injection**| Stable | Now fully programmatic via Named Pipe. |
| **Multi-Session Support** | Stable | Discovery, List, Switch, and History Sync integrated. |
| **Auto-Launch** | Stable | Hub launches Gemini CLI on-demand if missing. |
| **Process Cleanup Safety**| Stable | Cleanup scripts protect ancestors and "Omni" windows. |

### Resolved: Native Hub Integration
1.  **Built-in Listener**: Removed `ai_listener.py` dependency. All IPC logic is now in `AiCliService.cs`.
2.  **Performance**: Parallel discovery ensures that stale or invalid node processes don't delay connection to valid ones.
3.  **Filtering**: WMI query now filters out incompatible global gemini-cli processes.
4.  **Error Handling**: SignalR clients are now notified if the Hub fails to communicate with the AI CLI.

---

## Ultimate goal
The ability to Create, List, Switch-Between, Close and Interact with multiple CLI windows on the PC from the Android app through the hub as the middleman.
Full control has been established via the Hub-to-CLI IPC bridge.