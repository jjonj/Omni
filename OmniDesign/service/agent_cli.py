"""Agent entry point to the live OmniHub-managed OmniDesign native document API.

Use via OMCP run_command on the PC, not a second document store. This helper
can ask the existing Gateway to launch the registered tool; it never bypasses auth.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen

EDITOR_ROOT = "https://hub.rancorous.online/omnidesign/"


def permalink(diagram_id: str) -> str:
    return EDITOR_ROOT + "?" + urlencode({"diagram": diagram_id})


def request(base: str, route: str, method: str = "GET",
            payload: dict | None = None, headers: dict | None = None) -> dict:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = Request(base.rstrip("/") + "/api/v1/" + route, data=body, method=method,
                  headers={"Accept": "application/json", **({"Content-Type": "application/json"} if body else {}), **(headers or {})})
    try:
        with urlopen(req, timeout=15) as res:
            if "json" not in res.headers.get("Content-Type", "").lower():
                raise RuntimeError("OmniDesign returned non-JSON content (possibly a Gateway login page). "
                                   "Use the authorized private PC service route; no write was confirmed.")
            return json.load(res)
    except HTTPError as exc:
        try:
            detail = json.load(exc).get("detail", {})
            reason = detail.get("error", detail) if isinstance(detail, dict) else detail
            revision = detail.get("currentRevision") if isinstance(detail, dict) else None
        except (ValueError, OSError):
            reason, revision = exc.reason, None
        hint = {
            404: "Unknown ID or diagram not yet synchronized to this Hub.",
            410: "Diagram was archived; restore it before editing.",
            412: "Concurrent edit. Re-read the latest document and explicitly rebase; nothing was saved.",
            428: "Revision precondition or idempotency key missing.",
        }.get(exc.code, "")
        raise RuntimeError(f"HTTP {exc.code}: {reason}. {hint}" +
                           (f" Current revision: {revision}" if revision is not None else "")) from exc
    except (URLError, TimeoutError, OSError) as exc:
        raise RuntimeError(f"OmniHub/OmniDesign unreachable at {base} ({exc}). "
                           "Launch the private OmniDesign tool in Gateway, connect to the PC, then retry. "
                           "Offline-only IDs cannot be resolved until first sync; no edit was saved.") from exc


def ensure_gateway_tool(base: str) -> None:
    """Use the registered Gateway on-demand launcher; never start another daemon."""
    address = urlsplit(base)
    if address.hostname not in ("localhost", "127.0.0.1") or address.port != 8790:
        return
    health_url = base.rstrip("/") + "/api/health"

    def healthy() -> bool:
        try:
            with urlopen(health_url, timeout=2) as response:
                return json.load(response).get("service") == "omnidesign"
        except (URLError, TimeoutError, ValueError, OSError):
            return False

    if healthy():
        return
    launch = Request("http://127.0.0.1:3333/gateway/api/launch/omnidesign",
                     data=b"{}", method="POST", headers={"Content-Type": "application/json"})
    try:
        with urlopen(launch, timeout=8):
            pass
    except (HTTPError, URLError, TimeoutError, OSError):
        # The following fetch reports a clear, actionable transport error if
        # Gateway is offline, the registration is missing, or login is required.
        return
    for _ in range(12):
        if healthy():
            return
        time.sleep(0.5)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8790",
                        help="Reachable private OmniDesign service origin (or authenticated Gateway tool URL).")
    parser.add_argument("--no-launch", action="store_true", help="Do not ask local OmniHub Gateway to launch its registered tool.")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("families", help="List editor-owned stencil packs, templates and available node kinds.")
    draft = sub.add_parser("draft", help="Write a local editable v1 draft; NOT remotely saved.")
    draft.add_argument("--spec", help="JSON with family, title, nodes and links.")
    draft.add_argument("--family", default="architecture", help="Built-in stencil pack (or override spec family).")
    draft.add_argument("--template", help="Optional built-in starter template ID.")
    draft.add_argument("--title", help="Diagram title (or override spec title).")
    draft.add_argument("--output", required=True, help="Local native .omd.json draft path.")
    create = sub.add_parser("create", help="POST a revision-0 native draft to the authoritative PC store.")
    create.add_argument("--document", required=True, help="Local .omd.json draft; never replaced by an image.")
    read = sub.add_parser("read", help="Retrieve latest native document from permanent link or ID.")
    read.add_argument("link")
    read.add_argument("--output", help="Optional file for lossless JSON source.")
    patch = sub.add_parser("patch", help="CAS add/replace/remove native elements or rename diagram.")
    patch.add_argument("link")
    patch.add_argument("--revision", type=int, required=True, help="Revision read before editing; never guessed.")
    patch.add_argument("--operations", required=True, help="JSON file: list of operations or {operations:[...]}.")
    patch.add_argument("--key", help="Stable idempotency key; default is deterministic for ID, base revision and operations.")
    proposal = sub.add_parser("propose", help="Submit a full native revision-pinned document for user review.")
    proposal.add_argument("link")
    proposal.add_argument("--revision", type=int, required=True)
    proposal.add_argument("--document", required=True)
    proposal.add_argument("--summary", required=True)
    proposal.add_argument("--proposal-id", help="Stable proposal ID; default derived from content so retries deduplicate.")
    args = parser.parse_args()

    try:
        from pathlib import Path
        if args.action in ("draft", "families"):
            from agent_draft import catalog, write_draft
            if args.action == "families":
                print(json.dumps([{"id": p["id"], "family": p["family"],
                                   "stencils": [s["name"] for s in p["shapes"]],
                                   "templates": [t["id"] for t in p["templates"]]}
                                  for p in catalog()], indent=2))
                return 0
            if not args.spec and not args.title and not args.template:
                raise ValueError("Supply --spec, --title or --template; cannot save an unnamed empty diagram.")
            spec = json.loads(Path(args.spec).read_text(encoding="utf-8")) if args.spec else {}
            if not isinstance(spec, dict):
                raise ValueError("--spec must contain a JSON object")
            if not args.spec or args.family != "architecture":
                spec["family"] = args.family
            if args.template:
                spec["template"] = args.template
            if args.title:
                spec["title"] = args.title
            saved = write_draft(spec, args.output)
            print(json.dumps({"id": saved["id"], "revision": 0,
                              "path": str(Path(args.output).resolve()),
                              "status": "local-only; NOT remotely saved",
                              "next": "Use create --document with this same file when OmniHub is reachable."}, indent=2))
            return 0
        if args.action == "create":
            from server import validated
            from fastapi import HTTPException
            local = json.loads(Path(args.document).read_text(encoding="utf-8"))
            try:
                validated(local, revision=0)
            except HTTPException as exc:
                raise ValueError(f"Invalid native draft: {exc.detail}") from exc
            if not args.no_launch:
                ensure_gateway_tool(args.base_url)
            saved = request(args.base_url, "diagrams", "POST", local)
            if saved.get("id") != local["id"] or saved.get("revision") != 1:
                raise RuntimeError("Unexpected create receipt; verify by reading ID before claiming success.")
            print(json.dumps({"id": saved["id"], "revision": saved["revision"],
                              "link": permalink(saved["id"]), "status": "saved to PC OmniDesign"}, indent=2))
            return 0
        if not args.no_launch:
            ensure_gateway_tool(args.base_url)
        # The resolver validates link shapes and returns from the SAME SQLite DB.
        doc = request(args.base_url, "links/resolve?" + urlencode({"link": args.link}))
        if args.action == "read":
            output = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
            if args.output:
                from pathlib import Path
                Path(args.output).write_text(output, encoding="utf-8")
                print(f"Read {doc['id']} revision {doc['revision']} into {args.output}")
            else:
                sys.stdout.write(output)
            return 0

        if args.revision < 1:
            raise RuntimeError("A known positive base revision from a prior read is required.")
        # Do not reject a retried operation just because its first response was
        # lost: the server's durable idempotency receipt must handle that replay.
        diagram_id = doc["id"]
        if args.action == "patch":
            from pathlib import Path
            ops = json.loads(Path(args.operations).read_text(encoding="utf-8"))
            payload = ops if isinstance(ops, dict) else {"operations": ops}
            key = args.key or hashlib.sha256((diagram_id + ":" + str(args.revision) + ":" +
                        json.dumps(payload, sort_keys=True, separators=(",", ":"))).encode()).hexdigest()
            result = request(args.base_url, "diagrams/" + quote(diagram_id) + "/elements", "PATCH",
                             payload, {"If-Match": f'"rev-{args.revision}"',
                                       "Idempotency-Key": key})
            sys.stdout.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        else:
            from pathlib import Path
            edited = json.loads(Path(args.document).read_text(encoding="utf-8"))
            if edited.get("id") != diagram_id or edited.get("revision") != args.revision:
                raise RuntimeError("Proposal must preserve linked diagram ID and its read base revision.")
            proposal_id = args.proposal_id or "prp_" + hashlib.sha256(
                (diagram_id + str(args.revision) + args.summary +
                 json.dumps(edited, sort_keys=True, separators=(",", ":"))).encode()).hexdigest()[:32]
            result = request(args.base_url, "diagrams/" + quote(diagram_id) + "/proposals", "POST", {
                "proposalId": proposal_id, "baseRevision": args.revision,
                "document": edited, "summary": args.summary})
            sys.stdout.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        return 0
    except (RuntimeError, ValueError, KeyError) as exc:
        print(f"OmniDesign: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
