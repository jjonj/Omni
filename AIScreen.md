# AI Chat Screen Documentation

The AI Chat screen in OmniSync Android provides a robust interface for interacting with one or more Gemini CLI sessions running on the Hub (Windows PC). It uses a real-time SignalR connection for bi-directional communication.

## 1. Screen Layout & Layers

The screen is built using a `Scaffold` with several key layers:
1.  **Top Bar (`CenterAlignedTopAppBar`)**: Displays session name, status, and management controls.
2.  **Disconnected Banner**: A red error banner that appears at the top of the content area when the Hub connection is lost.
3.  **Chat Area (`LazyColumn`)**: The central scrollable list of messages.
4.  **Input Row**: A fixed text field and send button above the bottom panel.
5.  **Bottom Quick Action Panel**: A persistent floating panel containing hardware-emulation buttons and AI-specific controls.

## 2. Detailed Component Breakdown

### 2.1 Top Bar
*   **Title/Session Selector**:
    *   Clicking the title opens a **Dropdown Menu** of all active sessions.
    *   **Display Logic**: Shows "Disconnected" if offline, "Creating Session..." during launch, or the current session name (e.g., "Session 1234").
    *   **Rename Button**: Each session item in the dropdown has an `Edit` icon that opens a rename dialog (`signalRClient.renameAiSession`).
    *   **Switching**: Selecting an item calls `signalRClient.switchAiSession(pid)`.
*   **Actions**:
    *   **Add Icon (`+`)**: Calls `signalRClient.startNewAiSession()` to spawn a new process on the PC.
    *   **Close Icon (`X`)**: Calls `signalRClient.stopAiSession(selectedPid)` and clears local message history.

### 2.2 Chat Area (`LazyColumn`)
*   **Message Bubbles**:
    *   **"Me"**: Aligned to the **End**, uses `primaryContainer` color.
    *   **"AI"**: Aligned to the **Start**, uses `secondaryContainer` color.
    *   **"System" / "Error"**: Aligned to the **Center**, uses `errorContainer` with center-aligned text.
*   **Typing Indicator**: A custom animated bubble with three pulsing dots that appears when `aiStatus` contains "Thinking".
*   **Auto-Scroll Logic**: Automatically scrolls to the bottom when new messages arrive or when the AI starts thinking, with a 100ms delay to allow layout calculation.

### 2.3 Input Row
*   **TextField**: "Ask AI something...". Supports up to 3 lines.
*   **Send Button**:
    *   If text is present: Calls `signalRClient.sendAiMessage(text, pid)`.
    *   If text is empty: Sends a raw **Enter** key press to the Hub (`signalRClient.sendKeyEvent("INPUT_KEY_PRESS", VK_RETURN)`), useful for progressing interactive CLI prompts.

### 2.4 Quick Action Panel (Bottom)
Arranged in two rows of four/three buttons:
*   **Row 1**:
    *   **Esc**: Emulates `VK_ESCAPE`.
    *   **Up Arrow**: Emulates `VK_UP` (command history on PC).
    *   **Down Arrow**: Emulates `VK_DOWN`.
    *   **Yolo**: A macro button that sends `Ctrl+Y` (`VK_CONTROL` down -> `VK_Y` press -> `VK_CONTROL` up).
*   **Row 2**:
    *   **Zoom 1.5x / Unzoom**: Calls `signalRClient.setAiZoom(pid, level)`. Hub emulates `Ctrl + MouseWheel`.
    *   **Clear**: Calls `signalRClient.clearAiMessages(pid)`.
    *   **History**: Calls `signalRClient.requestAiHistory()` to reload previous context for the current session.

## 3. Key Behaviors & State Management

*   **Keyboard Handling**: The UI dynamically adjusts padding using `WindowInsets.ime` to ensure the input field and bottom panel stay visible when the Android keyboard is up.
*   **Persistence**: Sessions are managed by the Hub. If the app is closed and reopened, `getAiSessions()` is called to synchronize state.
*   **Connectivity**: Most controls are disabled via `isConnected` state from `MainViewModel` to prevent ghost actions when offline.
*   **Message Filtering**: Bubbles with empty or blank content are filtered out to prevent UI glitches.
