# TFT Set Transition Guide

This guide explains how to transition the TFT Board Optimizer to a new set (e.g., Set 17).

## Phase 0: Scraping and Automation

You can use the automated scraper to jumpstart a new set.

1.  **Save the Mobalytics Page**:
    -   Navigate to `https://mobalytics.gg/tft/set17/champions` (replace `set17` with the current set).
    -   Save the page as "Webpage, HTML Only" or similar to a local file (e.g., `mobalytics.html`).

2.  **Run the Scraper**:
    -   Use the tool in `tft_tools/tft_scraper.py`.
    ```bash
    python tft_tools/tft_scraper.py mobalytics.html --set 17 --out output_folder
    ```
    -   This will:
        -   Download all champion JPG icons.
        -   Download all trait SVG icons.
        -   Generate a `set17.json` file with correctly mapped traits and costs.
        -   **Automatic Filtering**: The script automatically identifies and removes "unique" traits (traits that only have a single unit assigned). This keeps the synergy list focused on multi-unit interactions.

3.  **Process Assets**:
    -   Move the downloaded icons to `assets/tft/set17/champions/` and `assets/tft/set17/traits/`.
    -   Move the generated `set17.json` to `assets/tft/data/set17.json`.

## Phase 1: Data and Assets

1.  **Create New Set JSON**:
    -   Place the new data file in `assets/tft/data/set17.json`.
    -   Ensure it follows the structure: `{ "set_name": "...", "units": [...], "trait_metadata": { ... } }`.
    -   **Important**: Units that require other units (like Nidalee requiring Neeko in Set 16) should have a `"requires": "UnitName"` property.

2.  **Update Set Configuration**:
    -   Modify `assets/tft/data/set_config.json`:
        ```json
        {
          "current_set": "set17"
        }
        ```

3.  **Update Unit ID Mapping**:
    -   Update `assets/tft/data/unit_id_map.json` with IDs for the Team Planner.

4.  **Update Default Disabled Units**:
    -   Create `assets/tft/data/default_disabled_set17.json` with a list of unit names that should be hidden by default for this set (e.g., `["Baron Nashor"]`).
    -   The system now uses set-specific factory defaults and set-specific `localStorage` keys, ensuring a clean reset of master exclusions with every set upgrade.

5.  **Update Composition Rules**:
    -   Modify `assets/tft/data/comp_rules.json` to reflect new level requirements, cost limits, and auto-includes.

## Phase 2: Logic and Addons

Set-specific mechanics (like unique scoring for certain traits or the "Unlock" system) should be implemented as **Addons**.

1.  **Modify `js/tft_addons.js`**:
    -   If the new set has no "Unlock" system, you may eventually remove or disable the `UnlockAddon`.
    -   Create a new class (e.g., `Set17RulesAddon`) extending `TFTAddon`.
    -   Implement the necessary hooks:
        -   `onInit()`: Setup constants.
        -   `modifySynergyBase()`: Add global bonus traits (like Set 16's Targon/Piltover).
        -   `modifyTraitIncrement()`: Handle special unit contributions (like Set 16's Annie Arcanist count).
        -   `modifyScore()`: Apply penalties for forbidden comps, level restrictions, or diversity bonuses.

2.  **Example Addon Skeleton**:
    ```javascript
    class Set17RulesAddon extends TFTAddon {
        modifyScore(result, board, emblems, targetSize, mode, mustIncludeNames) {
            let { score, counts } = result;
            // Add custom logic here
            return { score, counts };
        }
    }
    ```

## Phase 3: Registration

You must register the new addons in `js/tft.js` in **two places**:

1.  **Main Thread (`loadTFTData`)**:
    ```javascript
    optimizer = new TFTOptimizer(tftData.units, tftData.trait_metadata);
    optimizer.addAddon(new Set17RulesAddon(optimizer, compRules));
    // optimizer.addAddon(new UnlockAddon(optimizer)); // Keep only if applicable
    ```

2.  **Worker Thread (`createOptimizerWorker`)**:
    ```javascript
    if (type === 'init') {
        optimizer = new TFTOptimizer(data.units, data.traitsData);
        optimizer.addAddon(new Set17RulesAddon(optimizer, data.compRules));
        // optimizer.addAddon(new UnlockAddon(optimizer));
        return;
    }
    ```

## Phase 4: Testing

1.  **Update Logic Tests**:
    -   Update `TestScripts/TFT/test_tft_logic.js` to point to the new set data and register the new addons.
2.  **Run Tests**:
    ```bash
    node TestScripts/TFT/test_tft_logic.js
    ```

## Troubleshooting and Lessons Learned

### 1. SPA Data Extraction
Mobalytics is a Single Page Application. The champion data is not in the HTML tags but embedded in a massive JSON block (`window.__PRELOADED_STATE__`).
-   **Challenge**: Standard regex often fails on this block due to escaped quotes and nested structures.
-   **Solution**: Use a **bracket-matching algorithm** (implemented in `tft_scraper.py`) to find the start of the JSON and count `{` and `}` to find the true end.

### 2. Asset Filename Normalization
Mobalytics CDN names do not always match the internal slugs or the names our application expects.
-   **Challenge**: Trait icons often had a `24-` prefix (e.g., `24-bastion.svg`) which caused 404s in the UI.
-   **Solution**: The scraper now automatically strips the `24-` prefix and ensures filenames match the `icon_url` generated in the JSON.

### 3. Application Dependencies (The "Items" Array)
The UI calls `renderEmblemPool`, which performs a `.filter()` on `tftData.items`.
-   **Challenge**: If the `items` array is missing from the set JSON, the entire application will crash on load.
-   **Solution**: The scraper now automatically generates the `items` array by creating an "Emblem" entry for every synergy found in the data.

### 4. Worker/Main Thread Sync
The optimizer runs in background workers for performance.
-   **Challenge**: Registering an addon in `tft.js` only updates the main thread. The worker will still use the old logic or crash if it doesn't have the same addon registered.
-   **Solution**: Always update the `createOptimizerWorker` blob and ensure the worker receives the `currentSet` ID during its `init` message to conditionally load the correct addons.
