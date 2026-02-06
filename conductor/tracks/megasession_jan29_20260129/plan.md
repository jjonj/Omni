# Implementation Plan: Megasession - OmniSync System Improvements

This plan outlines the steps to fulfill the tasks defined in the specification for the Hub, Android, and Web components.

## Phase 1: Hub Hotkey Settings Improvements (WPF)

- [x] Task: TDD - Create tests for `MainViewModel` hotkey management (Delete, Category change).
- [x] Task: Fix Hotkey Deletion bug by aligning `DeleteHotkeyCommand` parameter with `HubSettingsService.RemoveHotkey`.
- [x] Task: Implement `Categories` collection in `MainViewModel` and bind it to a new `ComboBox` in `MainWindow.xaml` hotkey row.
- [x] Task: Update `HotkeyConfig.Key` display to use a ReadOnly `TextBox` with appropriate styling in `MainWindow.xaml`.
- [x] Task: Implement expander state persistence using a Dictionary in `MainViewModel` and attached properties or view-level logic.
- [x] Task: Verify Phase 1 changes and run tests.
- [x] Task: Conductor - User Manual Verification 'Phase 1: Hub Hotkeys' (Protocol in workflow.md)

## Phase 2: Android App Core Enhancements

- [x] Task: TDD - Create tests for the new reconnection retry logic.
- [x] Task: Implement robust reconnection logic in `SignalRClient` with the specified retry schedule (0, 10, 30, 60, 120, 1800s).
- [x] Task: Add lifecycle observer to trigger reconnection when the app returns to the foreground.
- [x] Task: Update the horizontal keyboard feature to activate globally across screens (excluding specific future exclusions).
- [x] Task: Verify Phase 2 changes on a device/emulator.
- [x] Task: Conductor - User Manual Verification 'Phase 2: Android Core' (Protocol in workflow.md)

## Phase 3: Android File Explorer & AI Screen Enhancements

- [x] Task: Implement scaling font size for the path `TextBlock` in the Folder view based on string length.
- [x] Task: Add "Copy Path" to the long-press menu for folders in the File Explorer.
- [x] Task: Update the Image Viewer to display the current filename at the top or bottom.
- [x] Task: Refactor File Screen state management to only restore the text viewer if a file was active upon exit.
- [x] Task: Implement auto-scroll to bottom upon entering the AI screen.
- [x] Task: Add "Model Select" UI to the AI screen that communicates with the Gemini CLI via the Hub.
- [x] Task: Increase the maximum zoom level in the AI screen's zoom logic.
- [x] Task: Verify Phase 3 changes.
- [x] Task: Conductor - User Manual Verification 'Phase 3: Android UI' (Protocol in workflow.md)

## Phase 4: Omni Web & TFT Updates

- [x] Task: Update the TFT hotkey scheme in `TFT.html` (or associated JS) to use 'E' and 'T' prefixes for emblems and traits.
- [x] Task: Verify Phase 4 changes in a browser.
- [x] Task: Conductor - User Manual Verification 'Phase 4: Web/TFT' (Protocol in workflow.md)

## Finalization

- [x] Task: Perform a final build and run of the entire system.
- [x] Task: [checkpoint: <sha>] Final project-wide verification.
