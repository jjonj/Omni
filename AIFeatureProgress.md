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
The gateway has been refined significantly through iterative testing:

**Key Findings & Solutions:**
- **Window Identification**: Initial title-based matching was too broad, often picking up TortoiseGit log windows. We implemented a strict **Title + Window Class** matching system. It now specifically targets `OMNI_GEMINI_INTERACTIVE` and verifies the window class is either `ConsoleWindowClass` or `CASCADIA_HOSTING_WINDOW_CLASS` (Windows Terminal).
- **Safe Copying**: Using `Ctrl+C` for scraping terminal output was found to be destructive, often sending a SIGINT signal that cancelled the AI's response processing. We switched to **`Ctrl+Insert`**, which is the standard Windows "Safe Copy" that does not trigger interrupts.
- **Focus Management**: Windows protects against background processes "focus stealing." We implemented an **Alt-key "jab" technique** combined with multiple `SetForegroundWindow` attempts and `ShowWindow` calls to reliably bring the terminal to the foreground before typing.
- **Stabilization Logic**: The listener now waits 5 seconds after sending a command before the first copy attempt. It then uses a loop to compare "Cleaned" versions of the console history (removing spinners/artifacts) until the output length stops growing, ensuring a complete response is captured.
- **Click-to-Focus Safety**: Added physical clicks to the terminal prompt area to ensure keyboard focus, followed by de-selection clicks to prevent highlighted text from being mistaken for a new prompt.

---

### 4. Testing & Validation (`TestScripts/AIFeature`)

The AI test suite has been moved to a dedicated `AIFeature` subfolder for better organization:
- `test_ai_integration.py`: Validates the full SignalR roundtrip.
- `poc_gemini_control.py`: Refines the UI automation logic.
- `test_read_gemini_cli.py`: Specifically tests the console buffer reading.

---

## Current Status & Next Steps

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **SignalR AI Relay** | Stable | Verified with `test_ai_integration.py` |
| **Interactive Terminal Injection** | Stable | Uses `Ctrl+Insert` and Window Class filtering |
| **Focus Management** | Robust | Employs Alt-key jab and HWND-based verification |
| **Output Scraping** | Stable | Uses artifact cleaning and stabilization detection |

**Upcoming Improvements:**
1.  **Direct Hub Commands**: Allow the AI to emit JSON payloads to trigger native Hub actions.
2.  **Multi-session Support**: Support multiple concurrent Gemini windows for different context domains.