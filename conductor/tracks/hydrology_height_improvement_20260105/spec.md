# Specification: Hydrology Heightmap Improvements & Bug Fixes

## Overview
This track aims to refine the hydrology-based height generation by improving coastal transitions, eliminating BFS "staircasing" artifacts through advanced smoothing, and fixing the non-functional river sinuosity slider.

## Functional Requirements
1. **Enhanced Coastal Transitions:**
   - Implement a dynamic blending mechanism (Coastal Ramp) between the hydrology interior and base fractal coastline using the `outline` buffer.
   - Explore and potentially implement elevation snapping at river mouths to ensure they meet sea level naturally.
2. **Artifact Removal (BFS Staircasing):**
   - Explore and implement smoothing techniques for the distance fields used in interpolation (e.g., iterative Gaussian blur or Bilinear approximation).
   - Ensure transitions between rivers and ridges are visually smooth and free of "stepping."
3. **Bug Fix: River Sinuosity Slider:**
   - Investigate why the `riverSinuosity` parameter doesn't visibly change the output. 
   - Increase the jitter scale in `refineRiverPaths` if necessary to make the effect more pronounced.
   - Ensure the slider correctly triggers a regeneration pass.
4. **Combined Layer Visualization:**
   - Ensure all layers (Base Noise, Hydrology, Detail) are correctly blended and integrated.

## Acceptance Criteria
- [ ] No visible "stairs" or "steps" in the distance field interpolation.
- [ ] River mouths blend naturally into the sea level.
- [ ] Moving the "River Sinuosity" slider visibly changes the "wiggliness" of the river paths.
- [ ] The generation process remains performant and robust.
