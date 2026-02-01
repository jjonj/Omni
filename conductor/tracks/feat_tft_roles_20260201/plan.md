# Implementation Plan - TFT Unit Role Columns

## Phase 1: Data Preparation
- [x] Task: Update `assets/tft/data/set16.json` with role assignments.
    - [ ] Add `"role": "Tank"` to assigned units.
    - [ ] Add `"role": "AP Carry"` to assigned units.
    - [ ] Add `"role": "AD Carry"` to assigned units.
    - [ ] Add `"role": "Fighter"` to assigned units.
- [ ] Task: Conductor - User Manual Verification 'Data Preparation' (Protocol in workflow.md)

## Phase 2: UI Structure & Styling
- [ ] Task: Update `TFT.html` styles for the matrix layout.
    - [ ] Define grid styles for role columns within each cost group.
    - [ ] Ensure consistent widths for role columns.
    - [ ] Add column headers for Roles (Tank, AP Carry, AD Carry, Fighter) above the unit pool.
- [ ] Task: Update `renderUnitPools` in `tft.js`.
    - [ ] Modify logic to group units by both cost AND role.
    - [ ] Implement empty cell handling for roles with no units at a specific cost.
    - [ ] Maintain support for Smart Sort and Alphabetical filtering within the new grid.
- [ ] Task: Conductor - User Manual Verification 'UI Structure & Styling' (Protocol in workflow.md)

## Phase 3: Final Integration & Cleanup
- [ ] Task: Verify interactions across the new grid.
    - [ ] Test click to add.
    - [ ] Test drag and drop functionality.
    - [ ] Test context menu (disable unit).
- [ ] Task: Refine mobile responsiveness for the matrix layout.
- [ ] Task: Conductor - User Manual Verification 'Final Integration & Cleanup' (Protocol in workflow.md)
