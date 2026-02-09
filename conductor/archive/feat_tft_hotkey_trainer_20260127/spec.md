# Specification: TFT Hotkey Trainer

## 1. Overview
The Hotkey Trainer is a dedicated training mode within the TFT Helper designed to improve the user's speed and proficiency with keyboard shortcuts. It presents random configuration challenges (units, traits, emblems, levels) that must be configured and "solved" within a strict 10-second window.

## 2. Functional Requirements

### 2.1. UI - Sidebar Panel
- A persistent, toggleable sidebar panel dedicated to the Trainer.
- Displays the current challenge requirements clearly.
- Features a large, prominent numerical 10-second countdown timer.
- Includes a "Start/Next Challenge" button and a "Stop" button.

### 2.2. Challenge Generation
- Randomly selects 1-3 units for the "Must Include" list.
- Randomly selects 1-2 traits with specific target breakpoints.
- Randomly selects 0-2 emblems to be activated.
- Randomly selects a target Level (typically 6-9).

### 2.3. Success Criteria & Verification
To complete a challenge, the user must:
1.  Configure the TFT Helper UI (Must Include units, Traits, Emblems, Level) to match the challenge using hotkeys.
2.  Trigger the Solver.
3.  Trigger the "Copy Team Planner Code" action from any result.

**Verification Logic:**
- The trainer will hook into the "Copy" action (button click or hotkey).
- Upon copy, it will verify:
    - The current "Must Include" list matches the challenge.
    - The currently selected emblems match the challenge.
    - The target level matches the challenge.
- It does **not** need to verify if the solver result is "correct" for the configuration, only that the configuration inputs match the prompt and the copy action was performed.

### 2.4. Timer Logic
- The timer starts immediately when a challenge is generated.
- If the verification passes before the timer hits 0, it's a "SUCCESS".
- If the timer hits 0 before verification, it's a "FAIL".
- Success/Fail stats should be displayed (e.g., "5/7 Correct").

## 3. Non-Functional Requirements
- **Latency:** The verification check must be instantaneous upon the copy action.
- **Reset:** Challenges should reset the solver state (clear must-include, etc.) to ensure a fresh start for every trial.

## 4. Acceptance Criteria
- [ ] User can toggle the Trainer sidebar.
- [ ] Random challenges are generated with units, traits, emblems, and levels.
- [ ] The 10s timer counts down and triggers a failure if time runs out.
- [ ] The system correctly detects a "Success" when inputs match and the copy hotkey is pressed.
- [ ] Input verification correctly identifies mismatches in units or emblems.

## 5. Out of Scope
- Verification of solver result validity.
- Integration with external leaderboards.
