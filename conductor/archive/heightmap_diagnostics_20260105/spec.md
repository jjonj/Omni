# Specification: Heightmap Generation Diagnostics & Hillshade Toggle

## Overview
This track introduces a series of diagnostic sublayers to the "Water" category that visualize the incremental stages of the hydrology-based heightmap generation process. Additionally, it refactors the hillshading logic to be a user-controlled toggle.

## Functional Requirements
1.  **Incremental Visualization:** 
    -   Expose intermediate steps of the `HydrologySystem.interpolateHeightAdv` process as selectable sublayers.
    -   Stages to include: Base Noise, Distance Fields, Linear Baseline, Shaped Valleys, Jitter/Detail, Softened Result, and Final Blended Landmass.
2.  **Hillshade Toggle:**
    -   Add a global or per-layer toggle `showHillshade` (default: true).
    -   Apply hillshading to `height`, `heightAdv`, and all new height-based diagnostic sublayers when the toggle is active.
3.  **Buffer Management:**
    -   Introduce intermediate buffers (e.g., `heightStage1`, `heightStage2`) or a reusable `heightDiag` buffer to capture these states during the calculation pass.

## Non-Functional Requirements
-   **Performance:** Diagnostic views should not significantly degrade generation speed unless active.
-   **Memory:** Optimize buffer usage to prevent excessive RAM consumption on low-end devices.

## Acceptance Criteria
-   [ ] User can cycle through all heightmap generation stages in the "Water" diagnostic menu.
-   [ ] Hillshading can be toggled on/off for both "Height" and "HeightAdv" layers.
-   [ ] The transitions between diagnostic stages correctly reflect the logic in `HydrologySystem.js`.

## Out of Scope
-   Real-time animation of the generation process.
-   Editing the terrain directly through these diagnostic views.
