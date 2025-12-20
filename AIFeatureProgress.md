# OmniSync AI Feature Progress

This document tracks the implementation, architecture, and status of AI-related features within the OmniSync ecosystem. The AI integration bridges the mobile experience with the power of a local or cloud-based Gemini CLI.

## Architectural Overview

The AI system operates as a distributed loop:
1.  **Mobile Client (Android)** sends a prompt via SignalR to the Hub.
2.  **Omni Hub (C#)** broadcasts the prompt to all authenticated listeners.
3.  **AI Listener (Python)** captures the prompt, injects it into a Gemini CLI instance, scrapes the result, and sends it back to the Hub.
4.  **Omni Hub** broadcasts the response and status updates back to the Mobile Client.

---

## Component Deep-Dive

### 1. Android Frontend (`OmniSync.Android`)

#### `AiChatScreen.kt`
The primary user interface for AI interaction. It features a modern, Material 3 chat layout with:
- **Bi-directional Messaging**: Uses `SignalRClient.aiMessages` to display a reactive list of chat bubbles.
- **Typing Indicators**: Monitors `aiStatus` to show a smooth dot-animation when the PC is processing a request.
- **IME Padding**: Ensures the input field remains visible when the software keyboard is active.

#### `SignalRClient.kt`
The communication backbone. It exposes `StateFlow` objects for `aiMessages` and `aiStatus`.

---

### 2. Message Broker (`OmniSync.Hub`)

#### `RpcApiHub.cs`
The SignalR hub that manages the traffic.
- **Event Relay**: `SendAiMessage` relays prompts from Android to the CLI. `SendAiResponse` relays output from the CLI back to Android.
- **Status Management**: `SendAiStatus` allows the listener to report states like "AI Responding..." or "Thinking...".

---

### 3. AI Gateway (`OmniSync.Cli`)

#### `ai_listener.py`
The gateway has evolved from UI automation to a robust programmatic control system:

**Key Findings & Solutions:**
- **Programmatic Hook**: We moved away from brittle UI automation (typing and scraping) to a **Named Pipe Programmatic Hook**. A custom listener was added to the Gemini CLI (`packages/cli/src/utils/remoteControl.ts`) that opens a pipe at `\\.\pipe\gemini-cli-<pid>`.
- **Bidirectional Events**: The CLI now supports `RemotePrompt` and `RemoteResponse` events. The listener injects prompts directly into the model's stream and captures the full response buffer upon completion.
- **Reliability**: This method eliminates issues with window focus, "focus stealing" protections, and terminal character artifacts. It also correctly handles multi-paragraph responses without risk of truncation or corruption from "safe copy" timing issues.
- **PID Detection**: The Python listener dynamically discovers the correct Gemini process PID to establish the pipe connection, supporting various launch environments (bundled node vs global npm).

---

### 4. Testing & Validation (`TestScripts/AIFeature`)

The AI test suite has been moved to a dedicated `AIFeature` subfolder for better organization:
- `run_full_ai_test.py`: Orchestrates the launch of the CLI, Listener, and runs the integration test.
- `test_ai_integration.py`: Validates the full SignalR roundtrip via Named Pipes.
- `diagnose_gemini_window.py`: (Legacy) Used for identifying window classes.

---

## Current Status & Next Steps

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **SignalR AI Relay** | Stable | Verified with `test_ai_integration.py` |
| **Named Pipe Injection** | Stable | Programmatic and robust |
| **Response Capture** | Stable | Captured via event emitter after stream finish |
| **Multi-line Support** | Stable | Verified with long story prompts |

**Upcoming Improvements:**
1.  **Direct Hub Commands**: Allow the AI to emit JSON payloads to trigger native Hub actions.
2.  **Multi-session Support**: Support multiple concurrent Gemini windows for different context domains.