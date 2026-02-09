# Implementation Plan: Custom Horizontal QWERTY Keyboard & F-Key Support

## Phase 1: Design & Mockup
- [x] Task: Create interactive HTML/CSS/JS mockup of the custom QWERTY keyboard
    - [ ] Implement staggered layout with physical keyboard padding
    - [ ] Implement toggleable number row logic
    - [ ] Implement visual feedback (key highlights)
    - [ ] Implement sound feedback using placeholder audio
- [ ] Task: Conductor - User Manual Verification 'Design & Mockup' (Protocol in workflow.md)

## Phase 2: Android UI Foundation
- [x] Task: Create/Update Android App Settings for Keyboard
    - [x] Add `keyboard_sound_enabled` boolean setting
    - [x] Add `show_keyboard_number_row` boolean setting
- [x] Task: Implement Orientation Listener in Remote Control Fragment/Activity
    - [x] Detect rotation to landscape
    - [x] Implement full-screen immersive mode (Hide Nav/Status bars)
- [x] Task: Conductor - User Manual Verification 'Android UI Foundation' (Protocol in workflow.md)

## Phase 3: Custom Keyboard Implementation
- [x] Task: Implement Keyboard UI in Jetpack Compose
    - [x] Map mockup layout to Compose components
    - [x] Respect "Show Number Row" setting
    - [x] Integrate haptic and sound feedback (respecting settings)
- [x] Task: Implement F-Key Dropdown in Portrait Mode
    - [x] Add F-key button to `RemoteControlScreen`
    - [x] Implement Dropdown/Bottom-sheet for F1-F12 selection
- [x] Task: Conductor - User Manual Verification 'Custom Keyboard Implementation' (Protocol in workflow.md)

## Phase 4: Integration & Injection
- [ ] Task: Write failing unit tests for key event mapping and SignalR dispatch
- [ ] Task: Connect Custom Keyboard keys to `SignalRClient`
    - [ ] Send key down/up or char events for QWERTY
    - [ ] Send specific F-key codes for the new dropdown
- [ ] Task: Verify end-to-end injection on Windows Hub
- [ ] Task: Conductor - User Manual Verification 'Integration & Injection' (Protocol in workflow.md)
