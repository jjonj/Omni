# Implementation Plan: Heightmap Generation Diagnostics & Hillshade Toggle

## Phase 1: Infrastructure & Buffer Management [x]
- [x] Task 1.1: Add diagnostic buffers to `IslandGenerator.js`.
  - Add `heightStage0` through `heightStage6` to the `buffers` object.
- [x] Task 1.2: Implement Hillshading Toggle in `UIManager.js` and `Config.js`.
  - Add `showHillshade: true` to `DEFAULT_CONFIG`.
  - Add a toggle in the UI (e.g., in the Height/Water context panels).
- [x] Task 1.3: Red Phase - Buffer Allocation Validation.
  - Create `TestScripts/AIFeature/test_height_diagnostics.js` to verify buffer existence and hillshade state initialization.
- [x] Task 1.4: Green Phase - Pass Infrastructure Tests.
- [x] Task: Conductor - User Manual Verification 'Infrastructure' (Protocol in workflow.md)

## Phase 2: Hydrology Pipeline Refactoring [x]
- [x] Task 2.1: Modify `HydrologySystem.interpolateHeightAdv` to capture intermediate stages.
  - Stage 0: Base Noise (from `hBuf` copy).
  - Stage 1: Distance Fields (pack `distToRiver` and `distToRidge` into a visual buffer).
  - Stage 2: Linear Baseline (interpolation without shaping).
  - Stage 3: Shaped Valleys (after `shapedWeight`).
  - Stage 4: Detailed Topology (after `slopeNoise`).
  - Stage 5: Softened Topology (after softening pass).
- [x] Task 2.2: Implement `Stage 6: Final Blend` in `IslandGenerator.calculate`.
  - Capture the state after the `hydrologyInfluence` blend.
- [x] Task 2.3: Red Phase - Pipeline Snapshot Validation.
  - Update `test_height_diagnostics.js` to verify that diagnostic buffers are populated with non-zero data during calculation.
- [x] Task 2.4: Green Phase - Pass Pipeline Tests.
- [x] Task: Conductor - User Manual Verification 'Pipeline Refactoring' (Protocol in workflow.md)

## Phase 3: Rendering & Visualization [x]
- [x] Task 3.1: Update `Config.js` and `LayerRenderer.js` with new sublayers.
  - Add the 7 height stages to the `water` layer's `sublayers` list.
  - Update `LayerRenderer.renderPixel` to handle the new `subMode` IDs.
- [x] Task 3.2: Refactor `LayerRenderer.js` Hillshading.
  - Extract hillshading into a reusable helper function.
  - Update `height`, `heightAdv`, and new diagnostic sublayers to respect `options.showHillshade`.
- [x] Task 3.3: Red Phase - Visual Output Validation.
  - Verify that switching sublayers correctly updates the pixel data.
  - Verify that the hillshade toggle affects the final color values.
- [x] Task 3.4: Green Phase - Pass Rendering Tests.
- [x] Task: Conductor - User Manual Verification 'Rendering & Visualization' (Protocol in workflow.md)

## Phase 4: Polish & Performance [x]
- [x] Task 4.1: UI Styling for Sublayer Navigation.
  - Ensure labels for Stages 0-6 are clear in the UI.
- [x] Task 4.2: Final Integration Check.
  - Verify all toggles (Rivers, Hillshade, Diagnostics) interact correctly.
- [x] Task 4.3: Memory Optimization (Optional).
  - Decision: Current memory usage (approx 28MB for stages) is acceptable for targeted hardware.
- [x] Task: Conductor - User Manual Verification 'Final Integration' (Protocol in workflow.md)
