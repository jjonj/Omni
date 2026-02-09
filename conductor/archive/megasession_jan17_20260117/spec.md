# Specification: Megasession Jan 17 (Omni Web TFT Priority)

## Overview
A comprehensive megasession to address multiple pending tasks across the OmniSync ecosystem, with an initial focus on refining and fixing the Omni Web TFT module, followed by Android and Chrome extension improvements.

## Functional Requirements

### Phase 1: Omni Web TFT (High Priority)
1.  **Smart Sort Visibility:** In "Smart Sort" mode, units that are already present in the active input zone (Must Include or Current Team) must be hidden from the selection list.
2.  **Level Settings:** Update the default active levels from [6, 8] to just [7].
3.  **Dependency Fix:** Resolve the `Uncaught ReferenceError: require is not defined` in `tft_evolution_test.js`.
4.  **Unit List Completion:** Add "Tibbers" to the unit list.
5.  **Annie/Must Include Bug:** Investigate and fix the failure to generate solutions when "Annie" is in the "Must Include" zone.
6.  **Hotkeys:** Add a dedicated hotkey to toggle "Smart Sort" mode.
7.  **Must Include Traits:** Show active/partial traits under the must-include units and traits drag box, similar to result tiles.
8.  **Unit Replacement Fix:** Fix the "Replace" feature in result tiles where clicking a replacement unit does nothing.

### Phase 2: Android Dashboard & Sleep Tracker
1.  **Dashboard Layout:** Fix the layout/scrolling issue where the log area is pushed below the screen fold.
2.  **Sleep Tracker Persistence:** Fix the sleep tracker so it continues tracking through app force-closes or restarts during the night, ensuring it doesn't incorrectly stop and report "User active" without a Hub connection.

### Phase 3: Chrome Extension & Hub Integration
1.  **Error Logging:** Implement a mechanism for the Chrome extension to report errors to the Hub, which then logs them to `CHROME_EXTENSION_ERROR.log`.
2.  **Vivaldi Extension Reload:** Investigate and implement a method to programmatically reload the extension within the Vivaldi browser.

## Non-Functional Requirements
-   **Test-Driven Development:** All logic fixes (especially the Annie/Must Include bug) must be preceded by a failing unit test.
-   **Sequential Execution:** Tasks must be moved to `TasksDone.txt` and committed individually as per `Tasks.txt` instructions.

## Acceptance Criteria
-   Omni Web TFT functions correctly with Annie in Must Include.
-   TFT Smart Sort hides already-selected units.
-   Android Dashboard is fully scrollable/viewable.
-   Sleep tracking is resilient to app restarts.
-   Chrome Extension errors are visible in Hub logs.

## Out of Scope
-   New feature additions not listed in the current `Tasks.txt`.
