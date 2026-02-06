# Specification: Project Selector Popup

## Overview
Add a hotkey-activated "Project Selector" popup to the Omni Hub. This UI allows users to quickly launch one or many configured projects using either mouse clicks or keyboard shortcuts (numbers 1-9).

## Functional Requirements
- **Trigger:** A new global hotkey (configurable, default to be determined, e.g., Ctrl+Alt+P) triggers the popup.
- **Data Source:** Uses the existing `Project` list managed by `HubSettingsService`.
- **UI Components:**
    - **Centered Overlay:** A WPF window that appears in the center of the primary monitor.
    - **Numbered List:** Displays projects in a scrollable list.
    - **Keyboard Shortcuts:** Projects 1-9 can be launched by pressing the corresponding number key.
    - **Multi-Launch Logic:** 
        - Clicking/Selecting a project executes its associated `ProjectLauncherService` actions.
        - Upon selection, a 2-second countdown timer starts. 
        - If another project is selected before the timer expires, the timer resets to 2 seconds.
        - The popup closes automatically when the timer reaches zero.
- **Visual Style:** Consistent with the existing WPF Hub theme (Dark/Glassy).

## Non-Functional Requirements
- **Responsiveness:** The popup should appear nearly instantaneously.
- **Focus Management:** The popup should take focus upon opening to capture keyboard input immediately.

## Acceptance Criteria
- [ ] Pressing the hotkey opens the centered Project Selector.
- [ ] Pressing '1' launches the first project in the list.
- [ ] Clicking a project launches it and starts a 2-second "auto-close" countdown.
- [ ] Clicking a second project within those 2 seconds resets the timer.
- [ ] The popup closes after 2 seconds of inactivity following a launch.
- [ ] The list is scrollable if more than 9 projects exist.

## Out of Scope
- Editing project configurations within this popup (editing remains in the main Hub UI/Web Settings).
- Customizing colors per project card in this version.
