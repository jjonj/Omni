# Specification: Megasession Jan 10

## 1. Overview
This track covers a series of usability improvements, bug fixes, and feature additions for the OmniSync ecosystem, specifically targeting the **Files & Video Screen** and the **AI Screen** components of the Android Client and Windows Hub.

## 2. Scope

### 2.1 Files & Video Screen (Android)
*   **State Persistence:**
    *   **Scroll & Cursor:** When switching away from a text file and returning, the scroll position and cursor placement must be preserved.
    *   **File Open State:** Switching screens must *not* automatically close the currently open file. The file should remain open when returning to the screen.

### 2.2 AI Screen (Android & Hub)
*   **UX Improvements:**
    *   **Auto-Scroll:** Chat window must automatically scroll to the bottom when a new message arrives, *only if* the user was already at the bottom.
    *   **Ghost Echo Fix:** Fix the issue where a user's message is displayed a second time as if sent by the AI. Ensure the Hub/CLI correctly distinguishes user input from AI output.
    *   **File Link Navigation:** Clicking a file path link in the chat must correctly identify and open the *file*, rather than erroneously searching for a *folder*.

*   **Session Management:**
    *   **Reload All Sessions:** Replace the "Reset All Sessions" button with "Reload All Sessions".
        *   **Hub Logic:** The Hub must generate a detailed log file (e.g., `OMNI_SESSION_DEBUG_REPORT.LOG`) in the project root.
        *   **Log Content:** Compare the list of sessions displayed on Android vs. active sessions known to the Hub (ID, Name, Active Time, Status).
        *   **Action:** Analyze discrepancies (e.g., why old sessions weren't cleared) and force a reload of valid active sessions.
    *   **Browse Options:** The "Browse" dropdown option must prompt the user to choose between:
        *   `dir add` (Add to context)
        *   `start new session` (Open in new session)

*   **New Features:**
    *   **Focus Button:**
        *   **Action:** A button to bring the specific Gemini CLI window for the current session to the foreground on Windows.
        *   **Implementation:** Hub uses the tracking PID of the specific session to send a focus command (using Win32 APIs).
    *   **Quick Commands / Presets:**
        *   **UI:** A new button opening a dropdown of preset text/commands to insert into the input box.
        *   **Storage:** Presets must be stored on the **Windows Hub** to ensure synchronization across devices.
        *   **Default Presets:** `/model`, `/conductor:newTrack`, `/conductor:implement`, `Please run any final scripts and commit all changes`.
        *   **Customization:** Long-pressing the button (while text is in the input field) saves that text as a new preset.

### 2.3 General / Workflow
*   **Completion Protocol:** Upon finishing all tasks, the system must use `aispeak` to request access to the CLI repository for "yolo and choice render" issues before ending the turn.

## 3. Out of Scope
*   Browser Control Screen changes.
*   Android Home Screen Widgets.
*   Settings Screen (Dashboard button).
*   "Local First, WAN Fallback" (handled in a separate track).
