# Apni visual design study — OMN-0083

The current interactive visual study is [index.html](index.html), with [apni.css](apni.css) and [apni.js](apni.js). It is a **design reference**, not the live native OmniHub overlay. Work on native launcher implementation is independent of visual acceptance.

## Interaction contract

- **Launcher wheel:** one unified app, script and Setup launcher. Hover a category to expose its actions; hold/aim/release is illustrated and clicks remain preview-only.
- **Open Windows wheel:** separate direct gesture, not a mode nested under the launcher. Apps form the primary ring; hovering an app reveals its individual window thumbnails/fan.
- **Visual states:** Explore, Add, Edit, Empty group and Empty wheel are accessible through the study controls. Add/edit uses contextual in-wheel forms. Reset restores the example data.
- A damped pointer-responsive spring moves and settles the wheel; reduced-motion preference disables this effect.
- No real launches, native window thumbnails, shortcut registrations, persistent state or OS hooks are performed by this HTML study. Gesture labels are illustrative.

## OMN-0084 — stable outer-ring traversal (implemented in interactive study)

The radial hit-testing engine is [apni-navigation.js](apni-navigation.js), a **DOM-free, reusable** browser/Node module. This is mockup-level integration only: OMN-0073 owns the native OmniHub wheel overlay and OMN-0075 owns persistent drag/release Add Mode placement. Neither has to wait for the other to adopt the interaction contract.

- A cursor that **enters the category ring from the outer orbit** enters pass-through lock. Crossing the center, and even a missed/sparse pointer sample, retains the current category; the opposite outer action can still be aimed/released immediately. Lock is released on reaching the outer orbit or leaving the wheel.
- **Deliberate hover** on another inner category for 115 ms inside a 13-viewBox-unit steady radius switches it; an explicit category click or keyboard activation switches immediately. Ordinary movement from the center within the inner ring changes category immediately, with a 3-degree boundary hysteresis.
- A transient feedback line says “Cross through to keep … · pause on a group to switch.” The locked sector does not visually highlight just because the DOM path is hovered. Normal action hover is derived from geometry, not DOM hover. Holding Space no longer disables aiming, and releasing in the core/dead zone never acts on a stale outer selection.
- Add Mode uses the same lock, but geometric insertion gaps (including zero-action and empty-wheel first slots) **outrank** category/action hovers. A gap visibly highlights; clicking or holding Space/releasing on a gap opens the mockup insertion editor, without changing the active category.
- One-shot settle timer runs only while a possible intentional category hover is pending; there is no always-on pointer polling. Browser pointer coordinates are transformed through SVG's current screen matrix so the study's spring transform does not skew hit-testing.

**Reproduction:** Open `index.html` (or the interactive study), hover the top outer Daily action, move swiftly through the center to the opposite bottom action: Daily remains selected. Rest on an inner category for ~0.12 s: it switches. Select **Add**, sweep from an outer action across a new-category + marker to another outer action: the selected group remains stable; hover and click a + marker to see the exact insertion slot. Select **Empty group** and **Empty wheel** to see first-slot markers. Run the deterministic regression evidence with `node --test docs/Apni/apni-navigation.test.cjs` from the Omni repository root.

**Native handoff (OMN-0073 / OMN-0075):** Use `ApniNavigation.create({active})`; map native pointer positions into the 760×760 design coordinates, call `move(x,y,performance.now(),{categories,actions,windowsMode,addMode,emptyWheel,emptyCategory})` for active-wheel pointer events, and apply returned `category/action/gap/transitLocked`. Schedule **only** a one-shot `settle(now)` for non-null `dueAt`, cancel it on pointer/state change. `chooseCategory(index)` handles deliberate click/key category activation, `leave()` clears stale selection, `reset(index)` on wheel/mode/data reset. On release commit an action **only when the latest snapshot has an action**, or stage the indicated insertion gap in Add Mode. Module is plain JS with CommonJS export and browser global; it can be imported/ported to the native frontend without DOM coupling. Do not claim this prototype is running in OmniHub until that owner wires it.

## Evidence and references

The original OMN-0070 prototype is commit `ddfabd92`. Before and after captures, including launcher, open windows, add, edit and both empty states, are stored at `E:/_AgentScratch/Tasks/Omni/OMN-0083/Evidence/` and attached to the native visual review on OMN-0083.

Visual inspiration, not copied into application assets:
- Gola hero wheel: https://gsl0001.github.io/gola/assets/v109/hero.jpg
- Gola liquid theme: https://gsl0001.github.io/gola/assets/v107/themes/liquid.webp
- Gola window fan: https://gsl0001.github.io/gola/assets/v107/fan.webp

The design borrows layered circular material, illuminated rim, shallow depth, floating action satellites and compact window-instance previews. The actual markup, vectors, styling and interaction implementation are original to Apni.

## Review

Review the attached before/after screenshots and the interactive study. Judge overall visual quality, satellite spacing, category hierarchy, window thumbnails, and add/edit/empty states. Request aesthetic revisions directly on **OMN-0083**; do not block other Apni implementation tasks.
