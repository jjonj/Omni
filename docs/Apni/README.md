# Apni visual design study — OMN-0083

The current interactive visual study is [index.html](index.html), with [apni.css](apni.css) and [apni.js](apni.js). It is a **design reference**, not the live native OmniHub overlay. Work on native launcher implementation is independent of visual acceptance.

## Interaction contract

- **Launcher wheel:** one unified app, script and Setup launcher. Hover a category to expose its actions; hold/aim/release is illustrated and clicks remain preview-only.
- **Open Windows wheel:** separate direct gesture, not a mode nested under the launcher. Apps form the primary ring; hovering an app reveals its individual window thumbnails/fan.
- **Visual states:** Explore, Add, Edit, Empty group and Empty wheel are accessible through the study controls. Add/edit uses contextual in-wheel forms. Reset restores the example data.
- A damped pointer-responsive spring moves and settles the wheel; reduced-motion preference disables this effect.
- No real launches, native window thumbnails, shortcut registrations, persistent state or OS hooks are performed by this HTML study. Gesture labels are illustrative.

## Evidence and references

The original OMN-0070 prototype is commit `ddfabd92`. Before and after captures, including launcher, open windows, add, edit and both empty states, are stored at `E:/_AgentScratch/Tasks/Omni/OMN-0083/Evidence/` and attached to the native visual review on OMN-0083.

Visual inspiration, not copied into application assets:
- Gola hero wheel: https://gsl0001.github.io/gola/assets/v109/hero.jpg
- Gola liquid theme: https://gsl0001.github.io/gola/assets/v107/themes/liquid.webp
- Gola window fan: https://gsl0001.github.io/gola/assets/v107/fan.webp

The design borrows layered circular material, illuminated rim, shallow depth, floating action satellites and compact window-instance previews. The actual markup, vectors, styling and interaction implementation are original to Apni.

## Review

Review the attached before/after screenshots and the interactive study. Judge overall visual quality, satellite spacing, category hierarchy, window thumbnails, and add/edit/empty states. Request aesthetic revisions directly on **OMN-0083**; do not block other Apni implementation tasks.
