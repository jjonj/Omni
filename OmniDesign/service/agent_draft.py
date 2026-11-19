"""Construct native OmniDesign documents from agent-readable layouts.

The catalog is exported from the browser editor's built-in packs, not independently
reimplemented stencils. An offline draft is *not* a remote Hub save.
"""
from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
from uuid import uuid4

PACKS_FILE = Path(__file__).with_name("agent_packs.json")
PALETTE = {
    "canvas": "#14161a", "panel": "#1d2026", "ink": "#e6e2d8",
    "muted": "#a09a8c", "accent": "#e0a458",
}
SIDES = ("top", "right", "bottom", "left")


def uid(prefix: str) -> str:
    return prefix + str(uuid4())


def catalog() -> list[dict]:
    return json.loads(PACKS_FILE.read_text(encoding="utf-8"))


def families() -> list[str]:
    return [pack["id"] for pack in catalog()]


def generate(spec: dict) -> dict:
    """Create an editable document from a simple node/link graph or a built-in template."""
    if not isinstance(spec, dict):
        raise ValueError("Draft specification must be an object")
    packs = catalog()
    pack = next((p for p in packs if p["id"] == spec.get("family", "architecture")), None)
    if pack is None:
        raise ValueError("Unknown family; available: " + ", ".join(families()))
    template_id = spec.get("template")
    template = next((t for t in pack["templates"] if t["id"] == template_id), None)
    if template_id and template is None:
        raise ValueError("Unknown template for " + pack["id"] + ": " + str(template_id))
    # Explicit nodes/links take precedence; a selected template provides a useful starter.
    nodes = spec.get("nodes", template["nodes"] if template else [])
    links = spec.get("links", template["links"] if template else [])
    if not isinstance(nodes, list) or not isinstance(links, list):
        raise ValueError("nodes and links must be arrays")
    if len(nodes) > 300 or len(links) > 600:
        raise ValueError("Draft exceeds 300 nodes or 600 links")
    title = spec.get("title", template["title"] if template else "Untitled diagram")
    if not isinstance(title, str) or not 1 <= len(title) <= 240:
        raise ValueError("title must contain 1-240 characters")
    now = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    layer = uid("lyr_")
    description = spec.get("description", template["description"] if template else "")
    doc = {
        "format": "omnidesign", "schemaVersion": 1, "id": uid("dgm_"),
        "revision": 0, "title": title, "family": pack["family"],
        "metadata": {"createdAt": now, "updatedAt": now,
                     "originDeviceId": uid("dev_"), "forkedFrom": None,
                     "description": description, "tags": spec.get("tags", [])},
        "page": {"width": spec.get("width", 1200), "height": spec.get("height", 700),
                 "background": PALETTE["canvas"],
                 "grid": {"enabled": False, "size": 20, "snap": True}, "units": "px"},
        "theme": {"name": "R3 · diagram skill", "tokens": PALETTE.copy()},
        "layers": [{"id": layer, "name": "Systems and connections",
                    "visible": True, "locked": False}],
        "elements": [], "libraries": {"references": [{"id": "core", "version": "1"}],
                                      "embedded": []}, "assets": [],
    }
    refs: dict[str, dict] = {}
    for index, entry in enumerate(nodes):
        if not isinstance(entry, dict) or not isinstance(entry.get("key"), str) or not entry["key"]:
            raise ValueError("Each node needs a stable unique key")
        key = entry["key"]
        if key in refs:
            raise ValueError("Duplicate node key: " + key)
        source = next((p for p in packs if p["id"] == entry.get("pack", pack["id"])), None)
        if source is None:
            raise ValueError("Unknown stencil pack for " + key)
        stencil = next((s for s in source["shapes"] if s["name"] == entry.get("stencil")), None)
        if stencil is None:
            raise ValueError("Unknown stencil for " + key + " in " + source["id"]
                             + "; choices: " + ", ".join(s["name"] for s in source["shapes"]))
        fallback = stencil["fallback"]
        glyph = stencil.get("glyph")
        props = {**stencil.get("defaults", {}),
                 **({"omnidesign/glyph": glyph} if glyph else {}),
                 **entry.get("props", {})}
        ports = []
        port_templates = stencil.get("ports") or [
            {"name": side, "side": side, "direction": "both"} for side in SIDES
        ]
        for port in port_templates:
            ports.append({
                "id": uid("prt_"), "name": port["name"],
                "direction": port.get("direction", "both"),
                "anchor": {"side": port["side"], "t": .5},
                **({"dataType": port["dataType"]} if port.get("dataType") else {}),
            })
        label = entry.get("text", entry.get("title", stencil["title"]))
        if not isinstance(label, str):
            raise ValueError("Node label must be a string for " + key)
        element = {
            "id": uid("el_"), "type": "node", "layerId": layer,
            "parentId": None, "order": index,
            "geometry": {"x": entry.get("x", 70 + (index % 4) * 275),
                         "y": entry.get("y", 105 + (index // 4) * 165),
                         "width": entry.get("width", stencil.get("width", 210)),
                         "height": entry.get("height", stencil.get("height", 112)),
                         "rotation": 0},
            "shape": {"libraryId": source["id"], "name": stencil["name"],
                      "version": source["version"], "fallback": fallback},
            "ports": ports,
            "label": {"text": label,
                      "style": {"fontSize": 18, "textColor": PALETTE["ink"]}},
            "style": {"fill": PALETTE["panel"], "stroke": PALETTE["ink"],
                      "strokeWidth": 2, "cornerRadius": 12,
                      **stencil.get("style", {}), **entry.get("style", {})},
            "semantic": {"role": stencil["role"], "data": props},
        }
        if entry.get("subtitle") or entry.get("items") or entry.get("chips"):
            element["content"] = {
                "title": label, "subtitle": entry.get("subtitle", ""),
                "items": [{"id": uid("itm_"), "text": item} for item in entry.get("items", [])],
                "chips": [{"id": uid("itm_"), "text": item} for item in entry.get("chips", [])],
            }
        refs[key] = element
        doc["elements"].append(element)
        ref = {"id": source["id"], "version": source["version"]}
        if ref not in doc["libraries"]["references"]:
            doc["libraries"]["references"].append(ref)

    for index, link in enumerate(links):
        if not isinstance(link, dict) or link.get("from") not in refs or link.get("to") not in refs:
            raise ValueError("Link references an unknown node key")
        a, b = refs[link["from"]], refs[link["to"]]
        from_side, to_side = link.get("fromSide", "right"), link.get("toSide", "left")
        if from_side not in SIDES or to_side not in SIDES:
            raise ValueError("Link side must be top/right/bottom/left")

        def endpoint(node: dict, side: str, time: float | None = None) -> dict:
            if time is not None:
                return {"type": "anchor", "nodeId": node["id"],
                        "anchor": {"side": side, "t": time}}
            port = next((p for p in node["ports"] if p["anchor"]["side"] == side),
                        node["ports"][0])
            return {"type": "port", "nodeId": node["id"], "portId": port["id"]}

        kind = link.get("kind", "flow")
        timed = min(.95, .23 + .115 * index) if pack["family"] == "sequence" else None
        route = link.get("route", "straight" if kind in ("message", "return")
                         else "orthogonal")
        element = {
            "id": uid("el_"), "type": "edge", "layerId": layer,
            "parentId": None, "order": len(doc["elements"]),
            "source": endpoint(a, from_side, timed),
            "target": endpoint(b, to_side, timed),
            "route": {"mode": route, "waypoints": [], "manual": False},
            "label": {"text": link.get("label", "")},
            "style": {"stroke": PALETTE["muted"] if kind in ("association", "link")
                      else PALETTE["accent"], "strokeWidth": 2,
                      **({"strokeDash": [6, 5]} if kind == "return" else {}),
                      **link.get("style", {})},
            "semantic": {"role": kind, "data": {"kind": kind}},
        }
        doc["elements"].append(element)

    # Same schema and graph checks as the PC service, without starting it.
    from server import validated
    from fastapi import HTTPException
    try:
        validated(doc, revision=0)
    except HTTPException as exc:
        raise ValueError(f"Invalid native draft: {exc.detail}") from exc
    return doc


def write_draft(spec: dict, output: str) -> dict:
    document = generate(spec)
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n",
                      encoding="utf-8")
    return document
