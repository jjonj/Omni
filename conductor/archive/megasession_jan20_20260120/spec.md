# Specification: Megasession Jan 20 2026

## Overview
This megasession encompasses a series of improvements, bug fixes, and feature additions across the OmniSync ecosystem (Hub, Android, and Web). The goal is to refine user interactions, fix long-standing UI/UX issues, and add specific automation capabilities.

## Functional Requirements

### 1. Windows Hub
- **Instance Management:** Prevent multiple instances of `OmniSync.Hub.exe`. A second instance must signal the primary instance to bring its window to the foreground and then immediately terminate.
- **Startup Routines:** Implement a "Quick Actions" system that runs once per day upon Hub initialization. 
    - Initial action: Browser tab cleanup.
    - Must persist the last run date to ensure it only executes once even if the Hub restarts.

### 2. Android App
#### UI/UX
- **Keyboard Handling:** Overwrite back button behavior when the soft keyboard is visible to ensure it only dismisses the keyboard without triggering navigation.
- **Log Scrolling (Dashboard):** Disable automatic scrolling to the bottom when new log entries arrive on the Dashboard.
- **Auto-scroll Logic (General):** Fix "Scroll to bottom" logic. Auto-scroll should only disable if the user manually scrolls *up*. Scrolling down while already at the bottom should not disable auto-scroll.
- **Folder View:** Remove the microphone icon from the folder/file browser view (retain it in the text viewer).

#### Features
- **Sleep Tracker:** Automatically pause tracking when connected to the Hub. Resume tracking automatically upon disconnection.
- **Browser Cleanup:** Support title-based cleanup patterns (e.g., `title:substring`). Matching should be case-insensitive.
- **Alarms:**
    - **Alarm #1:** Add a toggle for daily repetition.
    - **Alarm #2:** Add support for a specific future date (one-time alarm, disables after firing).

#### AI Screen
- **Tool Call Rendering:** Render `tool call: replace` and `tool call: edit` using a diff-style format (interleaved `+` and `-` lines) instead of a simple JSON block. Use yellow accents to maintain consistency with tool call styling.
- **Message Identity:** Messages sent to the AI via the Windows CLI must be displayed as sent by "Me" on the Android app, matching the identity of messages typed locally on the phone.

### 3. Omni Web & Chrome Extension
- **Website Selector:** Add a dropdown to the Web UI to switch between pre-defined URLs:
    - `google.com`
    - `http://10.0.0.37:5000`
    - `http://10.0.0.37:3333`
- **TFT Hotkeys:** Fix `Ctrl+A` hotkey consistency on the TFT page to ensure it correctly blocks input and activates the intended state every time.

## Acceptance Criteria
- Hub prevents duplicate instances and focuses the existing window.
- Android back button dismisses keyboard without navigating.
- Sleep tracker stops/starts based on Hub connection status.
- AI screen displays CLI-sent messages as "Me".
- AI tool calls for `edit`/`replace` are rendered as readable diffs.
- All tasks from `Tasks.txt` are verified and moved to `TasksDone.txt`.

## Out of Scope
- Any features in `Tasks.txt` not explicitly listed above or in the "General" sections.
