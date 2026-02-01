# Implementation Plan: TFT Hotkey Trainer

## Phase 1: UI Scaffolding & State Management [checkpoint: ab09ffb]
Build the foundational UI and the logic for generating and managing challenge states.

- [x] Task: Create the Trainer sidebar panel in `TFT.html` and style it in `style.css`.
- [x] Task: Implement `TrainerState` in `tft.js` to track active challenge, timer, and stats.
- [x] Task: Implement challenge generation logic (random units, traits, emblems, level).
- [x] Task: Write unit tests for the challenge generator to ensure valid combinations are produced.
- [x] Task: Implement the "Start/Stop" and "Reset UI" logic to clear existing solver configurations.
- [~] Task: Conductor - User Manual Verification 'Phase 1: UI Scaffolding & State Management' (Protocol in workflow.md) ab09ffb

## Phase 2: Timer & Verification Logic [checkpoint: ab09ffb]
Implement the countdown mechanics and the hook-based verification system.

- [x] Task: Implement the 10-second countdown timer with visual updates in the sidebar.
- [x] Task: Create a `verifyChallengeInputs` function that compares current UI state with the active challenge.
- [x] Task: Hook into the `copyResultCode` function in `tft.js` to trigger verification.
- [x] Task: Write unit tests for `verifyChallengeInputs` using mocked UI states.
- [x] Task: Implement Success/Failure feedback (visual cues and sound effects if applicable).
- [~] Task: Conductor - User Manual Verification 'Phase 2: Timer & Verification Logic' (Protocol in workflow.md) ab09ffb

## Phase 3: Integration & Polish [checkpoint: ab09ffb]
Refine the user experience and ensure the trainer integrates smoothly with existing hotkeys.

- [x] Task: Ensure all UI interactions (sidebar toggle, button clicks) work seamlessly.
- [x] Task: Display session statistics (Success/Total) in the sidebar.
- [x] Task: Add a keyboard shortcut to toggle the Trainer sidebar.
- [x] Task: Final end-to-end testing of the challenge flow (Start -> Configure -> Solve -> Copy -> Result).
- [~] Task: Conductor - User Manual Verification 'Phase 3: Integration & Polish' (Protocol in workflow.md) ab09ffb
