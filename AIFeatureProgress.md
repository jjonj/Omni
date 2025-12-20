# OmniSync AI Feature Progress

This document tracks the implementation, architecture, and status of AI-related features within the OmniSync ecosystem.

Always use cleanup_gemini_windows.py between test runs, or even better, adjust all scripts to call cleanup_gemini_windows.py as the first thing.

## Architectural Overview

The AI system has transitioned from brittle UI automation to a robust **Programmatic Named Pipe Hook**.

1.  **Mobile Client (Android)**: Sends prompts via SignalR to the Hub.
2.  **Omni Hub (C#)**: Broadcasts prompts to all authenticated listeners.
3.  **AI Listener (Python)**: Discovers running Gemini instances, connects to their unique named pipes (\.\pipe\gemini-cli-<pid>), and injects prompts.
4.  **Gemini CLI (Node/React)**: A custom `remoteControl.ts` server listens for `RemotePrompt` events, submits them to the model, and emits `RemoteResponse` events upon completion.
5.  **Response Relay**: The Python listener captures the response from the pipe and relays it back to the Hub.

---

The project uses two workspaces
D:\SSDProjects\Omni, the main omni project with the hub, test scripts and android app
D:\SSDProjects\Tools\gemini-cli a custom/forked version/repo of gemini CLI with custom hooks into the CLI to be used by Omni

## Component Deep-Dive

### 1. AI Gateway (`OmniSync.Cli`)

#### `ai_listener.py`
Refactored to support the Named Pipe architecture.
- **PID Discovery**: Automatically finds the correct `node` process running `bundle/gemini.js`.
- **Async I/O**: Uses `asyncio.to_thread` to handle synchronous pipe communication without blocking the SignalR loop.

#### `gemini-cli` Customizations
- **`remoteControl.ts`**: Implements the IPC server. Now includes debug logging for incoming prompts and outgoing responses.
- **`useGeminiStream.ts`**: Modified to emit `RemoteResponse` both after model turns and specifically when slash commands (like `/dir`) are handled internally, preventing IPC hangs.

---

### 2. Testing & Validation (`TestScripts/AIFeature`)

The test suite has been expanded to cover advanced scenarios:
- `test_auto_multi_cli.py`: Automates launching multiple Gemini instances and verifies parallel communication with each via unique pipes.
- `test_command_injection.py`: Validates slash command injection (`/dir add`) and context-aware file reading (tasks.txt).
- `run_full_ai_test.py`: Orchestrates the full stack for regression testing.
These are the first generation proof of concept test scripts
A new generation of scripts that do the same but indirectly by communicating with the hub is in the works

---

## Current Status & Known Issues

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **SignalR AI Relay** | Stable | Verified with automated tests. |
| **Named Pipe IPC** | Stable | High performance, no focus-stealing issues. |
| **Slash Command Injection**| Stable | Verified `/dir add` works correctly. |
| **Multi-Session Support** | Stable | Parallel injection to multiple PIDs verified. |
| **Response Detection** | Stable | Fixed by implementing persistent Turn-Finished detection. |
| **Process Cleanup Safety**| Stable | Cleanup scripts now protect ancestors and windows with "Omni" in title to avoid self-termination. **Note: `cleanup_gemini_windows.py` should be used between test runs to ensure a clean state, or invoked at the start of integration tests.** |

### Resolved: IPC Read Reliability
The readback hang in `test_command_injection.py` was resolved by:
1.  **turnFinished Event**: Modifying `useGeminiStream.ts` to emit a `[TURN_FINISHED]` message whenever the `streamingState` returns to `Idle`.
2.  **Persistent Collection**: Updating test scripts to maintain the connection and accumulate response chunks until the finished marker is received, ensuring multi-turn tool interactions are fully captured.
3.  **Descriptive Placeholders**: Ensuring slash commands (which might not produce model output) emit a `[Command Handled]` placeholder to prevent IPC timeouts.

---

## Ultimate goal
The ability to create, List, switch-between, close and interact with multiple CLI windows on the PC from the Android app through the hub as the middleman.
We are achieving this by first establishing full control over gemini cli, then integrating the control into the hub and finally android to give us the full CLI <--> Hub <--> Android. 
Android already has barebones AI control but its somewhat outdated.

## Next Steps
Develop hub versions of test scripts. The scripts should ONLY communicate with the hub and tell the hub to start CLIs, send slash commands and messages to the CLIs and getting back messages and events from AI etc.


## Completed Recently
- **Process Cleanup Safety**: `cleanup_gemini_windows.py` now uses `Get-Process` with window title filtering (`-notlike "*Omni*"`) to avoid killing the active Gemini CLI session.
- **IPC Read Reliability**: Implemented `[TURN_FINISHED]` marker in `gemini-cli` and updated listener logic.
