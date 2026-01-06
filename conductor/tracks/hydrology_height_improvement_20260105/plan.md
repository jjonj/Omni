# Implementation Plan: Improving Hydrology Height Generation

## Phase 1: Bug Fix - River Sinuosity & Investigation
- [x] Task 1.1: Debug `riverSinuosity` parameter flow.
  - Verify slider in `index.html` -> `UIManager.js` -> `main.js` -> `GenerationWorker.js`.
- [x] Task 1.2: Pronounce Jitter Effect.
  - Update `HydrologySystem.refineRiverPaths` to increase the visual impact of the sinuosity parameter.
- [x] Task 1.3: Red Phase - Sinuosity Validation.
  - Create `TestScripts/AIFeature/test_sinuosity.js` to verify that path coordinates change when the parameter is adjusted.
- [x] Task 1.4: Green Phase - Pass Sinuosity Tests.
- [x] Task: Conductor - User Manual Verification 'Sinuosity Fix' (Protocol in workflow.md)

## Phase 2: Enhanced Coastal Blending
- [x] Task 2.1: Implement Coastal Ramp logic.
  - Modify the blending in `IslandGenerator.js` to use the `outline` buffer as a multiplier for hydrology influence.
- [x] Task 2.2: Elevation Snapping at River Mouths.
  - Ensure `HydrologySystem.interpolateHeightAdv` forces mouth node elevations to match sea level correctly.
- [x] Task 2.3: Red Phase - Blending Validation.
- [x] Task 2.4: Green Phase - Pass Blending Tests.
- [x] Task: Conductor - User Manual Verification 'Coastal Blending' (Protocol in workflow.md)

## Phase 3: Distance Field Smoothing (Artifact Removal) [x]
- [x] Task 3.1: Distance Field Smoothing Pass.
  - Implement a softening pass specifically for the BFS distance fields in `HydrologySystem.js` to eliminate "staircase" artifacts.
- [x] Task 3.2: Refine Interpolation Logic.
  - Adjust the `shapedWeight` and `interpolateHeightAdv` loops to utilize smoothed distances.
- [x] Task 3.3: Red Phase - Smoothness Validation.
- [x] Task 3.4: Green Phase - Pass Smoothness Tests.
- [x] Task: Conductor - User Manual Verification 'Artifact Removal' (Protocol in workflow.md)

## Phase 4: Final Integration & Polish [x]
- [x] Task 4.1: Verify Layer Integration.
  - Ensure all diagnostic stages still work correctly with the new smoothing/blending.
- [x] Task 4.2: UI/UX Final Check.
- [x] Task: Conductor - User Manual Verification 'Final Integration' (Protocol in workflow.md)

## Phase 5: Iterative Refinement & Feedback [x]
- [x] Task 5.1: Implement Continuous Path-based Seeding for Distance Fields.
  - Modify `interpolateHeightAdv` to seed from every point in the `edge.path` rather than just nodes.
  - Utilize the Vector Propagation (Dead Reckoning) on this high-resolution seed set.
  - Goal: Eliminate node-based "ripple" artifacts (snake stomachs).
- [x] Task 5.2: Evaluate Smoothed Result.
- [x] Task: Conductor - User Manual Verification 'Distance Smoothing' (Protocol in workflow.md)

## Phase 6: Dynamic Coastal Profiles (Smoother Beaches) [x]
- [x] Task 6.1: Implement Coastal Steepness Noise Mask. (REVERTED)
- [x] Task 6.2: Apply Non-linear Coastal Remapping. (REVERTED)
- [x] Task 6.3: Add UI Controls for Beach Intensity. (REVERTED)

## Phase 7: Advanced Coastal Blending (Beach Exploration)
- [~] Task 7.1: Implement Mask-based Height Blending.
  - Experiment with using the coastal mask to blend between base noise and hydrology more selectively near shores.
- [ ] Task 7.2: Evaluate Beach Smoothness.
- [ ] Task: Conductor - User Manual Verification 'Beach Exploration' (Protocol in workflow.md)
