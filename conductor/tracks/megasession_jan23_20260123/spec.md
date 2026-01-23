# Specification: Megasession Jan 23 2026

## Overview
This megasession aims to resolve all pending tasks listed in `Tasks.txt`, focusing on Hub performance settings, Android Files/Video UI improvements, and Android AI screen robustness/UX enhancements.

## Functional Requirements

### 1. OmniSync.Hub
- **History Limit Setting:** Implement a setting in the Hub UI to limit history loading.
  - **Unit:** Character count (represented in thousands, e.g., "50" = 50,000 characters).
  - **Goal:** Prevent UI lag when history is extremely long.

### 2. Android: Files & Video Screen
- **Path Display:** Implement scaling font size for the directory path at the top left; longer paths use smaller fonts.
- **Copy Path:** Add "Copy path to clipboard" option to the long-press context menu for folders.
- **Image Viewer:** Display the filename of the currently viewed image.
- **Screen Persistence:** 
  - The screen should not reset or change what was shown when navigating away and back.
  - Only show the last opened text file if it was actively open before leaving.

### 3. Android: AI Screen
- **Navigation:** Always scroll to the bottom when entering the AI screen.
- **Focus Button:** Fix the non-functional focus button.
- **Model Selection:** Implement a way to select the Gemini CLI model (requires a request to the user for CLI interaction).
- **Auto-scroll:** Automatically scroll to the bottom when sending a new message.
- **Stopwatch:** Display a stopwatch counter at the bottom of the chat showing time (mm:ss) elapsed since the last message.
- **Thinking Robustness:** 
  - Ensure the "AI is working" state (animated "...") is robust.
  - It must show correctly when the server is slow or when switching back to a session while work is in progress.

## Acceptance Criteria
- Hub setting correctly limits loaded message history.
- Android Files screen maintains state and handles long paths gracefully.
- AI screen provides a responsive, informative interface with reliable "thinking" feedback.
- All changes are verified by building the respective modules and following project-specific test protocols.
