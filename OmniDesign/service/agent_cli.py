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


def request(base: str, route: str, method: str = "GET",
            payload: dict | None = None, headers: dict | None = None) -> dict:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = Request(base.rstrip("/") + "/api/v1/" + route, data=body, method=method,
                  headers={"Accept": "application/json", **({"Content-Type": "application/json"} if body else {}), **(headers or {})})
    try:
        with urlopen(req, timeout=15) as res:
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
