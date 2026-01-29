# Specification: Megasession - OmniSync System Improvements (Jan 29, 2026)

## Overview
This track encompasses a suite of enhancements across the Hub (WPF), Android (Kotlin/Compose), and Web (TFT) components. The goal is to polish existing UI behaviors, fix persistent bugs, and improve the overall user experience and reliability of the ecosystem.

## Functional Requirements

### 1. Hub Hotkey Settings (WPF)
- **Inline Category Editing:** Add a `ComboBox` to existing hotkey rows to allow changing their category.
- **Hotkey Visibility:** Clearly display the current hotkey combination for each entry (using a ReadOnly `TextBox` or similar).
- **Fix Deletion:** Resolve the bug where hotkeys do not disappear upon deletion.
- **Expander Persistence:** Ensure that adding or deleting a hotkey does not collapse all category groups in the UI.

### 2. Android App (Kotlin/Compose)
- **Horizontal Keyboard:** Enable the fullscreen horizontal keyboard feature across all app screens (with a mechanism for future exclusions).
- **Robust Reconnection:** Implement a retry schedule (0, 10, 30, 60, 120, 1800s) for SignalR disconnections. Trigger reconnection also when the app returns to the foreground.
- **File Explorer Improvements:**
    - **Path Scaling:** The path text at the top-left should scale its font size downward as the path length increases.
    - **Copy Path:** Add a "Copy Path" option to the long-press menu for folders.
    - **Image Viewer:** Display the filename of the currently viewed image.
    - **State Management:** The file screen should remember its specific state. Only show the last opened text file if it was actually open when the user left the screen.
- **AI Screen:**
    - **Auto-Scroll:** Ensure the view scrolls to the most relevant content (bottom) upon entering.
    - **Model Selection:** Add a mechanism to select the Gemini CLI model via the Hub.
    - **Enhanced Zoom:** Increase the maximum zoom level for the zoom feature.

### 3. Omni Web (TFT)
- **New Hotkey Scheme:** Update the emblem/trait scheme to use 'E' prefix for emblems (e.g., `ebi` for Bilgewater) and 'T' prefix for traits (e.g., `tgu` for Gunslinger).

## Acceptance Criteria
- All Hub UI behaviors (category edit, delete, expander state) work as described.
- Android reconnection follows the specified timing and triggers on app reopen.
- Android File Explorer features (scaling font, copy path, image name, state persistence) are verified.
- Android AI screen is improved with better scroll, zoom, and model selection.
- Web TFT hotkeys follow the new 'E'/'T' prefix convention.
