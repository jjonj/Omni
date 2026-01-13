# Test: Remote Control Screen Layout Fix

## Issue
There was a jagged overlapping issue at the border between the trackpad area and button panel, causing a janky look.

## Changes Made
1. Changed layout from Column to Box with layering
2. TrackpadArea now fills the entire screen
3. ButtonPanel is wrapped in a Surface with elevation (8.dp shadow, 2.dp tonal)
4. This creates a clean visual separation with a shadow effect

## Expected Result
- No jagged edges or misalignment between trackpad and button panel
- Clean, crisp border with a subtle shadow
- Button panel clearly sits on top of trackpad
- No visual artifacts or overlapping issues

## Test Steps
1. Build and deploy the app
2. Navigate to Remote Control screen
3. Observe the border between the gray trackpad area and black button panel
4. Verify there's a clean shadow/elevation effect
5. Test that button panel is movable/resizable (if applicable)
6. Test trackpad functionality still works correctly

## Files Changed
- `OmniSync.Android/app/src/main/java/com/omni/sync/ui/screen/RemoteControlScreen.kt`
