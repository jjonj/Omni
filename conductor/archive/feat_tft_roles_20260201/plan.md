# Implementation Plan - TFT Unit Role Columns

## Phase 1: Data Preparation [checkpoint: ab09ffb]
- [x] Task: Update `assets/tft/data/set16.json` with role assignments.
    - [x] Add `"role": "Tank"` to assigned units.
    - [x] Add `"role": "AP Carry"` to assigned units.
    - [x] Add `"role": "AD Carry"` to assigned units.
    - [x] Add `"role": "Fighter"` to assigned units.
- [x] Task: Conductor - User Manual Verification 'Data Preparation' (Protocol in workflow.md) ab09ffb

## Phase 2: UI Structure & Styling
- [x] Task: Update `TFT.html` styles for the matrix layout.
- [x] Task: Update `renderUnitPools` in `tft.js`.
- [x] Task: Conductor - User Manual Verification 'UI Structure & Styling' (Protocol in workflow.md) [checkpoint: ab09ffb]

## Phase 3: Final Integration & Cleanup
- [x] Task: Verify interactions across the new grid.
    - [ ] Test click to add.
    - [ ] Test drag and drop functionality.
    - [ ] Test context menu (disable unit).
- [x] Task: Refine mobile responsiveness for the matrix layout.
- [x] Task: Conductor - User Manual Verification 'Final Integration & Cleanup' (Protocol in workflow.md)
