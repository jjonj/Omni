(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.ApniNavigation = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  // Coordinates use the Apni 760x760 viewBox (center 380,380); all timing is ms.
  const GEOMETRY = Object.freeze({
    center: 380,
    coreRadius: 99,
    categoryOuterRadius: 244,
    outerEntryRadius: 265,
    actionRadius: 302,
    actionHitRadius: 41,
    gapHitRadius: 20,
    categoryGapRadius: 242,
    emptyCategoryGapRadius: 196,
    categoryDwellMs: 115,
    steadyRadius: 13,
    sectorMarginDegrees: 3,
  });

  const normalize = degrees => ((degrees % 360) + 360) % 360;
  const angularDistance = (a, b) => Math.abs(((a - b + 540) % 360) - 180);
  const segment = (angle, count) => count ? Math.round(normalize(angle + 90) * count / 360) % count : -1;
  const centerAngle = (index, count) => normalize(-90 + index * 360 / count);
  const distanceToOrbit = (radius, angle, targetRadius, targetAngle) =>
    Math.hypot(radius * Math.cos(angle * Math.PI / 180) - targetRadius * Math.cos(targetAngle * Math.PI / 180),
               radius * Math.sin(angle * Math.PI / 180) - targetRadius * Math.sin(targetAngle * Math.PI / 180));

  /** Pure coordinate navigation: no DOM, window hooks or idle polling. One settle() call on its dueAt when the pointer stops. */
  function create({ active = 0 } = {}) {
    let category = active, lastZone = "outside", transit = false, candidate = null;
    let point = null, hoveredAction = -1, hoveredGap = null, current = null;

    function snapshot() {
      return { category, action: hoveredAction, gap: hoveredGap, transitLocked: transit,
        dueAt: candidate ? candidate.since + GEOMETRY.categoryDwellMs : null };
    }
    function reset(active = 0) {
      category = active; lastZone = "outside"; transit = false; candidate = null;
      point = null; hoveredAction = -1; hoveredGap = null; current = null;
      return snapshot();
    }
    function chooseCategory(index) {
      category = index; transit = false; candidate = null; hoveredAction = -1;
      return snapshot();
    }
    function hit(x, y, context) {
      const dx = x - GEOMETRY.center, dy = y - GEOMETRY.center;
      const r = Math.hypot(dx, dy), a = normalize(Math.atan2(dy, dx) * 180 / Math.PI);
      const count = context.categories || 0, length = context.actions || 0;
      const zone = r < GEOMETRY.coreRadius ? "core" :
        r <= GEOMETRY.categoryOuterRadius ? "category" :
        r < GEOMETRY.outerEntryRadius ? "seam" : "outer";
      let gap = null, action = -1;

      if (context.addMode || context.emptyWheel || context.emptyCategory) {
        // Gap positions are the same as the visible markers. They outrank category sectors.
        const catSlots = context.emptyWheel ? 8 : count;
        if (catSlots) {
          const catR = context.emptyWheel ? GEOMETRY.emptyCategoryGapRadius : GEOMETRY.categoryGapRadius;
          for (let i = 0; i < catSlots; i++) {
            const at = context.emptyWheel ? i : i + 1;
            const angle = context.emptyWheel ? centerAngle(i, 8) : normalize(-90 + (i + .5) * 360 / catSlots);
            if (distanceToOrbit(r, a, catR, angle) <= GEOMETRY.gapHitRadius) {
              gap = { which: "cat", at }; break;
            }
          }
        }
        if (!gap && !context.emptyWheel && (context.addMode || context.emptyCategory)) {
          const slots = length || 1;
          for (let i = 0; i < slots; i++) {
            const angle = length ? normalize(-90 + (i + .5) * 360 / length) : 270;
            if (distanceToOrbit(r, a, GEOMETRY.actionRadius, angle) <= GEOMETRY.gapHitRadius) {
              gap = { which: "child", at: length ? i + 1 : 0 }; break;
            }
          }
        }
      }
      if (!gap && !context.emptyWheel && length && !context.windowsMode) {
        const index = segment(a, length);
        if (distanceToOrbit(r, a, GEOMETRY.actionRadius, centerAngle(index, length)) <= GEOMETRY.actionHitRadius) {
          action = index;
        }
      }
      let cat = -1;
      if (zone === "category" && !context.emptyWheel && count && !gap) {
        cat = segment(a, count);
        // A narrow boundary margin stops repeated category toggles from cursor jitter.
        const boundary = 180 / count;
        if (cat !== category && angularDistance(a, centerAngle(cat, count)) >= boundary - GEOMETRY.sectorMarginDegrees) cat = category;
      }
      return { radius: r, angle: a, zone, cat, action, gap };
    }
    function move(x, y, now, context) {
      if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(now)) return snapshot();
      const h = hit(x, y, context);
      // Only inward movement from an outer action starts a pass-through lock.
      // It survives the center crossing and is released at the far outer orbit.
      if ((lastZone === "outer" || lastZone === "seam") &&
          h.zone === "category" && point && point.radius >= GEOMETRY.categoryOuterRadius) transit = true;
      if (lastZone === "outer" && h.zone === "core") transit = true; // sparse pointer events
      if (h.zone === "outer") { transit = false; candidate = null; }
      lastZone = h.zone;
      current = { x, y, h, context };
      point = h;
      hoveredAction = h.action;
      hoveredGap = h.gap;

      if (h.cat >= 0 && h.cat !== category) {
        if (!transit) {
          category = h.cat; candidate = null; hoveredAction = -1;
        } else if (!candidate || candidate.cat !== h.cat ||
                   Math.hypot(x - candidate.x, y - candidate.y) > GEOMETRY.steadyRadius) {
          candidate = { cat: h.cat, x, y, since: now };
        }
      } else candidate = null;
      return snapshot();
    }
    function settle(now) {
      if (candidate && current && current.h.zone === "category" &&
          current.h.cat === candidate.cat &&
          now >= candidate.since + GEOMETRY.categoryDwellMs) {
        category = candidate.cat;
        candidate = null;
        // Keep the pass-through lock for the remaining outward travel.
        hoveredAction = -1;
      }
      return snapshot();
    }
    function leave() {
      lastZone = "outside"; transit = false; candidate = null; current = null;
      hoveredAction = -1; hoveredGap = null;
      return snapshot();
    }
    return { hit, move, settle, leave, reset, chooseCategory, snapshot };
  }

  return { create, GEOMETRY };
});
