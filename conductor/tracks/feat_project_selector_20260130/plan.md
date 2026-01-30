# Implementation Plan: Project Selector Popup

This plan outlines the steps to add a hotkey-activated Project Selector popup to the Omni Hub, allowing for rapid project launching via mouse or keyboard.

## Phase 1: Infrastructure & Data Binding
- [x] Task: Create `ProjectSelectorViewModel` to handle the project list and selection logic.
- [x] Task: Implement the 2-second auto-close timer logic in the ViewModel.
- [x] Task: Update `HubSettingsService` or `GlobalHotkeyService` to register the new Project Selector hotkey.
- [x] Task: Add a new command in `CommandDispatcher` (e.g., `SHOW_PROJECT_SELECTOR`) to trigger the popup.

## Phase 2: UI Implementation
- [x] Task: Create `ProjectSelectorWindow.xaml` and style it to match the existing WPF Hub "Dark/Glassy" theme.
- [x] Task: Implement the centered positioning logic for the window.
- [x] Task: Design the project item template with clear numbering (1-9) and hover animations.
- [x] Task: Ensure the list is scrollable for more than 9 projects.
- [x] Task: Implement keyboard event handling in `ProjectSelectorWindow` to map '1'-'9' keys to project launches.

## Phase 3: Integration & Testing

- [x] Task: Connect the `GlobalHotkeyService` trigger to show the `ProjectSelectorWindow`.

- [x] Task: Verify that launching a project via `ProjectLauncherService` works correctly from the popup.

- [x] Task: Test the multi-launch timer reset logic.

- [x] Task: Verify focus management (window takes focus on open, closes on ESC or timeout).

- [x] Task: Conductor - User Manual Verification 'Integration & Testing' (Protocol in workflow.md) [checkpoint: 18efa4a]
