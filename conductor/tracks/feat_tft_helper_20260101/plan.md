# Plan: TFT Helper Implementation

## Phase 1: Infrastructure & Data Setup [checkpoint: b74dc28]
- [x] Task: Create directory structure for TFT assets in `OmniSync.Web/www/assets/tft/` 10bf5ec
- [x] Task: Define JSON schema for TFT Set data (units, traits, items) 0a262d9
- [x] Task: Create `set16.json` based on `TFT.py` data def8c33
- [x] Task: Update `OmniSync.Hub` to serve these JSON files via a new API endpoint or static file serving abee7e6
- [ ] Task: Conductor - User Manual Verification 'Infrastructure & Data Setup' (Protocol in workflow.md)

## Phase 2: Web UI Foundation [checkpoint: 40334bb]
- [x] Task: Create `TFT.html` with basic Bootstrap/Material layout and top navigation tabs ee69162
- [x] Task: Implement Tab switching logic in `js/tft.js` ee69162
- [x] Task: Integrate `TFT.html` into the main application navigation (if applicable) ee69162
- [ ] Task: Conductor - User Manual Verification 'Web UI Foundation' (Protocol in workflow.md)

## Phase 3: Emblem Portal Implementation [checkpoint: 34d4ca6]
- [x] Task: Port `TFT.py` logic to JavaScript (`js/tft_optimizer.js`) 6f1189f
- [x] Task: Implement unit and emblem selection UI in the Emblem Portal tab 6f1189f
- [x] Task: Implement result display with unit icons and scores 6f1189f
- [x] Task: Write tests to verify JS optimizer matches `TFT.py` output for known inputs 6f1189f
- [ ] Task: Conductor - User Manual Verification 'Emblem Portal Implementation' (Protocol in workflow.md)

## Phase 4: Additional Tabs & Refinement
- [x] Task: Implement World Runes tab with iframe integration d85100e
- [x] Task: Create stubs for BronzeForLife and Director tabs d85100e
- [x] Task: Implement Configuration tab to display/reload JSON data d85100e
- [x] Task: Add icon harvesting instructions/placeholders for Brock and other set 16 units d85100e
- [ ] Task: Conductor - User Manual Verification 'Additional Tabs & Refinement' (Protocol in workflow.md)

## Phase 6: Icon Fixes
- [x] Task: Retry downloading missing Unit icons using kebab-case URLs 07b757c
- [x] Task: Retry downloading Trait icons using kebab-case URLs 07b757c
- [x] Task: Update `set16.json` to reflect local paths for newly downloaded icons 07b757c
- [ ] Task: Conductor - User Manual Verification 'Icon Fixes' (Protocol in workflow.md)
