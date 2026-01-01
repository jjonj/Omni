# Plan: TFT Helper Implementation

## Phase 1: Infrastructure & Data Setup
- [x] Task: Create directory structure for TFT assets in `OmniSync.Web/www/assets/tft/` 10bf5ec
- [ ] Task: Define JSON schema for TFT Set data (units, traits, items)
- [ ] Task: Create `set16.json` based on `TFT.py` data
- [ ] Task: Update `OmniSync.Hub` to serve these JSON files via a new API endpoint or static file serving
- [ ] Task: Conductor - User Manual Verification 'Infrastructure & Data Setup' (Protocol in workflow.md)

## Phase 2: Web UI Foundation
- [ ] Task: Create `TFT.html` with basic Bootstrap/Material layout and top navigation tabs
- [ ] Task: Implement Tab switching logic in `js/tft.js`
- [ ] Task: Integrate `TFT.html` into the main application navigation (if applicable)
- [ ] Task: Conductor - User Manual Verification 'Web UI Foundation' (Protocol in workflow.md)

## Phase 3: Emblem Portal Implementation
- [ ] Task: Port `TFT.py` logic to JavaScript (`js/tft_optimizer.js`)
- [ ] Task: Implement unit and emblem selection UI in the Emblem Portal tab
- [ ] Task: Implement result display with unit icons and scores
- [ ] Task: Write tests to verify JS optimizer matches `TFT.py` output for known inputs
- [ ] Task: Conductor - User Manual Verification 'Emblem Portal Implementation' (Protocol in workflow.md)

## Phase 4: Additional Tabs & Refinement
- [ ] Task: Implement World Runes tab with iframe integration
- [ ] Task: Create stubs for BronzeForLife and Director tabs
- [ ] Task: Implement Configuration tab to display/reload JSON data
- [ ] Task: Add icon harvesting instructions/placeholders for Brock and other set 16 units
- [ ] Task: Conductor - User Manual Verification 'Additional Tabs & Refinement' (Protocol in workflow.md)
