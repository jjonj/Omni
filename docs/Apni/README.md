# Apni radial launcher — design gate (OMN-0070)

Status: **Interactive prototype submitted for user visual approval; production implementation is not authorized by this mockup.**

## Review

- Native review on original TaskBoard card **OMN-0070** (Omni/Tasks).
- Stable local interactive presentation: http://127.0.0.1:3333/presentations/view/apni-radial-interaction
- Self-contained source: [index.html](index.html).
- Task evidence captures: E:/_AgentScratch/Tasks/Omni/OMN-0070/Evidence/apni-{compare,windows,add,emptyCat,emptyWheel,edit}.png.

## Design intent

1. Apni is a **desktop-only OmniHub feature**, sharing common Hub services and OmniSweep integration when eventually approved; no independent app or prototype service is started.
2. The **unified** wheel uses one hold/aim/release gesture: first ring categories (Apps, Scripts, OmniSweep, Workspaces, Tools, System), second ring actions. Hover an app with multiple windows to fan out previews.
3. The **separate** option keeps a launcher wheel and a fast dedicated window-switch wheel; the latter first groups by app, then exposes instance targets/previews. Example shortcuts are **illustrative**, not assigned system hotkeys.
4. **Add Mode** exposes dashed insertion gaps **between category slices** and **between child tiles**. Empty category and empty wheel examples are included. Clicking a gap opens an **in-wheel insert form**, not an OS prompt.
5. **Edit mode** opens a compact in-wheel editor to rename, assign type, reorder or remove a tile. Edits exist only in the page's in-memory state; no settings or system files are mutated.
6. Releasing on a target would invoke/focus it; center/outside/Escape cancels. Mouse placement and Space hold support gesture exploration; the walkthrough button simulates a full sequence.
7. Motion is restrained: short highlight transitions, nested ring entrance, and fanned preview cards. Respects prefers-reduced-motion.

## Reference boundary

The three **Gola original image assets** are linked externally in the reference gallery and labeled as such. None of those images are used as Apni UI assets. The radial vector art, ring layout, colors and editing states in this prototype are original Apni proposals.

- Gola hero: https://gsl0001.github.io/gola/assets/v109/hero.jpg
- Gola fan: https://gsl0001.github.io/gola/assets/v107/fan.webp
- Gola liquid theme: https://gsl0001.github.io/gola/assets/v107/themes/liquid.webp
- Gola website: https://gsl0001.github.io/gola/
- Original user conversation: https://chatgpt.com/c/6af7c639-9f54-83eb-872e-137d18e675b1

## Explicit non-goals / open approval

This is not production wheel code: **no global keyboard/mouse hooks, real window previews, process launches, OmniSweep commands, startup registration, Rust Hub integration or deployment**. Those belong to dependent implementation tasks **after** the native visual review receives the human's actual verdict. The UI reference screenshots display simulated window contents, not captured application windows.

Review the two modes, hover hierarchy, window fan, ghost insertion behavior, empty states, in-wheel editor and visual language. Approve or request changes in OMN-0070's native review. Do not infer approval from the prototype being viewable.
