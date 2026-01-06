# Specification: TFT Composition Templates

## Overview
Enable users to save their current team configuration as a "Comp" (template). These templates allow for rapid switching between different team archetypes by restoring units and level configurations with a single click.

## Functional Requirements

### 1. Composition Persistence (Save)
- **Save Trigger:** Add a "Save" button next to the "Clear" button in the **Current Team** header.
- **Data Captured:**
    - The list of units currently in the **Current Team** zone.
    - The state of all **Level** checkboxes.
- **Storage:** Data must be persisted in the browser's `localStorage` under a dedicated key (e.g., `tft_saved_comps`).
- **Iconography:** The first unit in the saved team will automatically become the visual icon for that Composition.

### 2. UI Integration
- **Header Removal:** Remove the "Units" header label from the Pools zone.
- **Comps List:** Create a new container for saved Compositions located directly above the alphabet filter buttons.
- **Management:** Each saved Comp icon must feature a small, persistent "x" button in the corner to allow for immediate deletion.

### 3. Loading Logic
- **Loading Trigger:** Left-clicking a saved Comp icon.
- **Impact on State:**
    - **Current Team:** Replaced entirely by the saved units.
    - **Must Include:** Replaced entirely by a *copy* of the saved units.
    - **Levels:** Restored to the state captured at the time of saving.
    - **Emblems:** The Selected Emblems zone must remain **untouched** during the load process.

## Acceptance Criteria
- [ ] Clicking "Save" creates a new icon in the Comps list using the first unit's image.
- [ ] Saved Comps survive page refreshes.
- [ ] Clicking a Comp icon correctly populates both "Current Team" and "Must Include" zones.
- [ ] Clicking a Comp icon correctly toggles the level checkboxes.
- [ ] Emblems selected before loading a Comp are still there after loading.
- [ ] Clicking the "x" on a Comp icon removes it from UI and storage.
- [ ] The "Units" header is no longer visible.
