# Specification: Custom Horizontal QWERTY Keyboard & F-Key Support

## Overview
Enhance the Remote Control experience on Android by introducing a custom, full-screen QWERTY keyboard that activates automatically in landscape mode. This custom input method bypasses the standard Android OS keyboard to provide a layout optimized for PC-style muscle memory. Additionally, a new UI element will be added to the portrait remote screen to allow sending Function Keys (F1-F12).

## Functional Requirements
### 1. Landscape QWERTY Keyboard
- **Auto-Activation:** The screen must automatically switch to the custom keyboard when the device is rotated to landscape orientation.
- **Physical Layout Mimicry:** Keys should be arranged with stagger and padding similar to a physical keyboard to support muscle memory.
- **Toggleable Number Row:** A row for keys `1` through `0` at the top.
    - Must be controllable via a setting.
    - When disabled, remaining keys (Alpha rows) should expand/re-layout to fill the vertical space.
- **Full Screen Immersive:** The Android navigation bar and status bar should be hidden when the keyboard is active.
- **Feedback System:**
    - **Visual:** Visual indication when a key is pressed (e.g., color change or popup).
    - **Audio:** Keyboard "click" sound on press.
    - **Settings:** Android app settings must include a toggle for the keyboard sound.

### 2. Portrait F-Key Support
- **Dropdown/Menu:** Add a button to the standard (portrait) remote control screen that opens a dropdown or overlay containing keys `F1` through `F12`.
- **Command Dispatch:** Tapping an F-key in the menu sends the corresponding key event to the Windows Hub.

## Non-Functional Requirements
- **Low Latency:** Key events must be dispatched to the Hub immediately via SignalR.
- **Layout Efficiency:** Maximum use of screen real estate with appropriate touch targets (min 44x44dp where possible).

## Acceptance Criteria
- [ ] Rotating to landscape on the Remote screen displays the custom QWERTY keyboard.
- [ ] Toggling the "Number Row" setting correctly adjusts the keyboard layout.
- [ ] Typing on the custom keyboard successfully injects text/keys into the target Windows PC.
- [ ] Portrait mode features a functional F-key selection menu.
- [ ] Keyboard click sounds can be enabled/disabled in the app settings.
- [ ] HTML Mockup matches the proposed design and feel.

## Out of Scope
- Support for complex multi-key combinations (e.g., Ctrl+Alt+Del) on the *custom* keyboard (standard modifiers only).
- Multi-language keyboard layouts (only US-QWERTY for now).
