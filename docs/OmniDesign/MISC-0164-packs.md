# OmniDesign stencil packs and editable templates — MISC-0164

The same native v1 document, SVG renderer and Canvas interactions serve all diagram families. A family is metadata and a starter-layout choice, **not a separate editing mode**. Nodes, typed ports, links, freehand paths, text, groups, layers and undo/redo are interoperable across packs.

## Included kits and templates

| Family | Symbols | Ready-to-edit starting template |
| --- | --- | --- |
| Architecture | Service, database, queue, client, cloud | Service architecture |
| Flowchart | Terminator, process, decision, document, I/O | Decision and retry |
| UML sequence | Actor, lifeline, activation | Request sequence with five timed messages/returns |
| UML state | Initial/final, state, choice | State machine with guard labels |
| Database ERD | Table, weak entity, relationship, attribute | Tables/fields with one-to-many links |
| Mind map | Topic, branch, detail | Central idea with branches |
| Timeline / roadmap | Phase, milestone, deliverable | Project milestone timeline |
| Network topology | Router, switch, host, firewall, subnet | Home LAN |
| Game design | Event, condition, action, resource, NPC | Survival-gameplay loop |
| Floorplan sketch | Room, door, window, furniture | Two-room adjacency sketch |
| Process / swimlane | Lane, task, decision | Three-team handoff |
| Wireframe | Screen, card, input, button | Editable mobile UI mockup |

Every template is generated as actual native nodes and attached edges. Select **Templates** from the header or library; choosing one creates a new local document without overwriting the current one. Choose **Kits** in the inspector or left rail (mobile: **Add**) to place any symbol from any pack by tapping the canvas. **Connect** links typed source/destination ports; choose link semantics in Kits before drawing or change an existing edge in Inspector. Layout remains freeform.

Selected specialist symbols expose their typed properties in Inspector (e.g., ERD primary key/schema, network IP/VLAN, state entry/exit, milestone date, NPC faction, room dimensions). Properties stay in `element.semantic.data` and the shape identity in `element.shape.libraryId/name/version`. Changing geometry/style, grouping, undo, SVG/PNG export, local autosave and native JSON import/export all reuse the v1 editor. ERD crow-foot, UML inheritance, composition, dependency, messages, transitions and network links use semantic edge kinds in the native model; labels and multiplicities remain editable.

**Not a formal modeling simulator.** UML sequence positions and swimlane boundaries are freeform; ERD does not enforce database DDL; floorplan dimensions are annotations, not surveyed CAD. Shapes still have ordinary drag/resize semantics.

## Adding a custom stencil/diagram library

Use the **Import .omdpack.json** action in Kits or the template picker. [Example file](../../OmniDesign/web/public/sample.quest-pack.omdpack.json) adds a Quest/Dialogue library with typed properties and a branching template.

A pack is declarative JSON (no JavaScript/HTML/SVG renderer):

```json
{
  "format": "omnidesign-pack", "schemaVersion": 1,
  "id": "my-pack", "version": "1", "name": "My Pack", "family": "game-system",
  "shapes": [{
    "name": "choice", "title": "Choice", "icon": "◇",
    "fallback": "diamond", "role": "game.choice", "width": 200, "height": 120,
    "defaults": {"condition": "hasItem"},
    "fields": [{"key": "condition", "label": "Condition", "type": "text"}]
  }],
  "templates": [{
    "id": "one-choice", "title": "Quest choice", "family": "game-system",
    "description": "Starter", "nodes": [
      {"key": "root", "stencil": "choice", "x": 200, "y": 180, "text": "Has item?"}
    ], "links": []
  }]
}
```

- Shape `fallback` is one of `rect`, `roundRect`, `ellipse`, `diamond`, `line`, `polygon`, `icon`; the runtime optionally decorates recognized `glyph` names (e.g. `database`, `screen`, `lifeline`, `cloud`). Unknown glyphs retain the primitive fallback.
- `fields` accept `text`, `number`, `boolean`, `select` (`options` for select). Use `ports` (name, side, direction, optional dataType) to customize connections; default is one bidirectional port on each side.
- Templates may provide node `subtitle`, `items`, `props`, `width`, `height`, and `links` with `from`, `to`, optional `kind`, `label`, side and route. Node keys are template-local; all instantiated IDs and port IDs are minted anew.
- Imported packs are stored in the browser/WebView's localStorage for future documents. **Used definitions are also embedded** in the native document: standard `libraries.embedded` fallback shape/port/style definitions plus `extensions["omnidesign/packDefinitions"]` for the richer pack catalogue and property editors. Reopening the same .omd.json on another device works without first importing the pack, including custom metadata. The original versioned definitions are not rewritten.
- This extends the existing v1 document without adding schema types or introducing an alternate canvas. An unknown or missing library is still editable as its persisted fallback primitive with intact semantic data.

OmniHub server sync (MISC-0165) and Android packaging (MISC-0168) are separate. Per-browser local library installation is not cross-device sync; portable documents retain their own embedded stencil definitions.
