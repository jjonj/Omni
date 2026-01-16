# Specification: Android AI Chat UX Improvements

## 1. Overview
Enhance the User Experience (UX) of the AI Chat screen on the Android client. This includes fixing auto-scroll behavior, improving visibility of AI status (waiting/thinking), adding alerts for required user input, and ensuring the "Focus" button un-minimizes the target window on Windows.

## 2. Functional Requirements

### 2.1 Auto-Scroll & Message Sending
*   **Req 2.1.1:** When the user sends a message, the chat list MUST automatically scroll to the bottom.
*   **Req 2.1.2:** The "Auto-scroll to bottom" trigger (e.g., a button or gesture) MUST be easily activatable even if the user is already near the bottom, fixing the current issue where scrolling up and back down is required.

### 2.2 Dialog Alerts
*   **Req 2.2.1:** When the AI session triggers a dialog requiring user choice (e.g., "Yes/No"), the Android app MUST play a subtle sound notification.
*   **Req 2.2.2:** When a dialog is active, the "Session List" button (top left) MUST highlight or flash with a distinct color (similar to the existing message activity indicator) to alert the user if they are in a different session or screen.

### 2.3 AI Status Visibility
*   **Req 2.3.1:** The Android client MUST visually distinguish between "Waiting for Server/CLI" (e.g., command sent, but CLI hasn't acknowledged yet) and "AI Thinking" (CLI acknowledged and is processing).
*   **Req 2.3.2:** If the CLI is busy but not yet streaming "thoughts" (e.g., initial startup or queue), the Android UI MUST show the "AI is thinking..." animation (or similar indeterminate progress) immediately after the user sends a message, rather than waiting for the first token from the server.

### 2.4 Remote Focus Un-minimize
*   **Req 2.4.1:** When the "Focus" button is pressed for an AI session, the Windows Hub MUST check if the target window is minimized.
*   **Req 2.4.2:** If the window is minimized, the Hub MUST restore/un-minimize it before attempting to bring it to the foreground.

## 3. Non-Functional Requirements
*   **Performance:** Auto-scroll animations should be smooth.
*   **Sound:** The alert sound should be non-intrusive.
*   **Reliability:** Status indicators must accurately reflect the state of the SignalR connection and session.

## 4. Acceptance Criteria
*   Sending a message immediately scrolls the view to the newest item.
*   Tapping the "Scroll to Bottom" button (if present) always works, regardless of current scroll offset.
*   Receiving a dialog request plays a sound and flashes the session button.
*   The "Thinking" animation appears immediately after sending a message, before the AI starts typing.
*   Clicking "Focus" on a minimized session window on Windows restores and activates it.

## 5. Out of Scope
*   Major UI redesign of the chat bubbles.
*   Changes to the underlying CLI logic (except where needed to expose status).
