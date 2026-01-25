# Specification: Megasession Jan 21 2026

## Overview
This megasession focuses on a broad set of improvements across the OmniSync ecosystem, targeting the Windows Hub's instance management, Android UI/UX refinements, AI screen enhancements, and Web UI utilities.

## Functional Requirements

### 1. Windows Hub
- **Single Instance Enforcement:** 
    - On launch, check if another instance is already running.
    - If running, signal the existing instance to bring its window to the foreground and then terminate the new instance.
- **Startup Routine System:**
    - Implement a "Quick Actions" framework that runs once per day on Hub start.
    - Add "Browser Tab Cleanup" as the first quick action (integrated with Chrome/Firefox extensions).
    - Configuration stored in `appsettings.json`; execution state (last run) stored in `startup_state.json`.

### 2. Android Client
- **UI/UX Refinements:**
    - **Swipe Sensitivity:** Increase horizontal threshold and require a stricter horizontal-to-vertical ratio for screen switching.
    - **Back Button Logic:** If the keyboard is visible, the back button must only dismiss the keyboard and not trigger screen navigation or activity dismissal.
    - **Folder View Cleanup:** Remove the microphone icon from the folder/file list view; keep it only in the text viewer.
- **Browser Control:**
    - Support tab cleanup based on tab titles (case-insensitive substring match).
- **AI Screen:**
    - **Diff Rendering:** Render `edit` and `replace` tool calls using a diff-style view (similar to Git diffs) instead of plain yellow blocks.
    - **Auto-scroll:** Fix the issue where the message list doesn't always scroll to the bottom upon receiving new messages.
    - **Clear Command:** Ensure the session history reloads correctly after a `clear` command is issued.
- **Alarms:**
    - Add support for custom "leave" text for future-dated alarms (Alarm 2), displayed on the dismiss screen.

### 3. Web UI
- **Website Selector:** Add a dropdown to the Omni Web UI with pre-defined options: `google.com`, `http://10.0.0.37:5000`, and `http://10.0.0.37:3333`.
- **TFT Hotkeys:** Ensure `Ctrl+A` (select all) works consistently on the TFT page.

## Non-Functional Requirements
- **Test-Driven Development:** All new logic (especially Hub instance management, Startup routines, and Android back/swipe logic) must be verified with unit tests first.
- **Performance:** Single instance check should be instantaneous to avoid UI lag on failed startup.

## Acceptance Criteria
- Running `omni.exe` twice results in the first window focusing and the second closing.
- Startup routines run exactly once per calendar day.
- Android back button dismisses keyboard without side effects.
- AI screen displays tool calls with readable diffs.
- Web dropdown correctly switches target URLs.
