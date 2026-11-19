"""OmniDesign v1: private, on-demand Gateway HTTP service.

The document is the portable native source. All writes are SQLite transactions;
the live revision and immutable snapshots are committed together. No auth layer
or collaboration engine is added: Gateway controls remote access to this tool.
"""
from __future__ import annotations

import base64
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
from typing import Any, Iterator

from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT.parent / "docs" / "OmniDesign" / "omnidesign.document.v1.schema.json"
DIST = ROOT / "web" / "dist"
DATA = Path(os.environ.get("OMNIDESIGN_DATA_DIR") or
            (Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
             / "OmniHub" / "OmniDesign"))
DB = DATA / "diagrams.sqlite3"
LIMIT = 8 * 1024 * 1024
ID = re.compile(r"^dgm_[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
PROPOSAL_ID = re.compile(r"^[A-Za-z0-9_-]{8,100}$")
SCHEMA_VALIDATOR = Draft202012Validator(json.loads(SCHEMA.read_text(encoding="utf-8")), format_checker=FormatChecker())


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def serialized(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def problem(status: int, message: str, **extra: Any) -> None:
    raise HTTPException(status, detail={"error": message, **extra})


def validated(document: Any, *, expected_id: str | None = None, revision: int | None = None) -> dict:
    if not isinstance(document, dict):
        problem(422, "Expected a native OmniDesign JSON object")
    try:
        encoded = serialized(document)
    except (TypeError, ValueError):
        problem(422, "Non-JSON or non-finite value")
    if len(encoded.encode("utf-8")) > LIMIT:
        problem(413, "Document exceeds 8 MiB")
    if document.get("schemaVersion") != 1:
        problem(422, "Unsupported schemaVersion (no implicit migration)")
    errors = sorted(SCHEMA_VALIDATOR.iter_errors(document), key=lambda e: tuple(map(str, e.path)))
    if errors:
        first = errors[0]
        problem(422, "Invalid native document", path=list(first.path), reason=first.message)
    if not ID.fullmatch(document["id"]):
        problem(422, "Invalid document ID")
    if expected_id is not None and document["id"] != expected_id:
        problem(422, "Path/document ID mismatch")
    if revision is not None and document["revision"] != revision:
        problem(422, "Document revision does not match expected base revision")

    # Schema checks individual field types; these checks enforce graph consistency.
    def unique(values: list[str], kind: str) -> None:
        if len(values) != len(set(values)):
            problem(422, "Duplicate " + kind + " ID")
    layer_ids = [layer["id"] for layer in document["layers"]]
    unique(layer_ids, "layer")
    elements = document["elements"]
    unique([element["id"] for element in elements], "element")
    nodes = {e["id"]: e for e in elements if e["type"] == "node"}
    by_id = {e["id"]: e for e in elements}
    for element in elements:
        if element["layerId"] not in layer_ids:
            problem(422, "Missing layer", elementId=element["id"])
        parent_id = element.get("parentId")
        ancestors = {element["id"]}
        while parent_id is not None:
            if parent_id in ancestors:
                problem(422, "Group cycle", elementId=element["id"])
            ancestors.add(parent_id)
            parent = by_id.get(parent_id)
            if parent is None or parent["type"] != "group" or parent["layerId"] != element["layerId"]:
                problem(422, "Invalid group parent/layer", elementId=element["id"])
            parent_id = parent.get("parentId")
        if element["type"] == "node":
            unique([p["id"] for p in element["ports"]], "port")
            if "content" in element:
                unique([i["id"] for k in ("items", "chips") for i in element["content"][k]], "content item")
        if element["type"] == "edge":
            for endpoint in (element["source"], element["target"]):
                if endpoint["type"] == "free":
                    continue
                target = nodes.get(endpoint["nodeId"])
                if target is None:
                    problem(422, "Dangling edge node", elementId=element["id"])
                if endpoint["type"] == "port" and not any(
                    p["id"] == endpoint["portId"] for p in target["ports"]
                ):
                    problem(422, "Dangling edge port", elementId=element["id"])
    # Python jsonschema treats JSON numbers as valid even for gigantic exponents;
    # do not save non-finite values anywhere in a nested extension or geometry.
    def finite(obj: Any) -> bool:
        if isinstance(obj, dict):
            return all(finite(v) for v in obj.values())
        if isinstance(obj, list):
            return all(finite(v) for v in obj)
        return not isinstance(obj, float) or math.isfinite(obj)
    if not finite(document):
        problem(422, "Non-finite number")
    return document


@contextmanager
def connection(*, writing: bool = False) -> Iterator[sqlite3.Connection]:
    db = sqlite3.connect(DB, timeout=10, isolation_level=None)
    db.row_factory = sqlite3.Row
    try:
        db.execute("PRAGMA busy_timeout=10000")
        db.execute("PRAGMA foreign_keys=ON")
        if writing:
            db.execute("BEGIN IMMEDIATE")
        yield db
        if writing:
            db.execute("COMMIT")
    except Exception:
        if writing:
            db.execute("ROLLBACK")
        raise
    finally:
        db.close()


def initialize() -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    with connection() as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA synchronous=FULL")
        db.executescript("""
        CREATE TABLE IF NOT EXISTS diagrams(
            id TEXT PRIMARY KEY, revision INTEGER NOT NULL,
            title TEXT NOT NULL, family TEXT NOT NULL, description TEXT,
            tags TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
            archived INTEGER NOT NULL DEFAULT 0, document TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS revisions(
            diagram_id TEXT NOT NULL REFERENCES diagrams(id),
            revision INTEGER NOT NULL, saved_at TEXT NOT NULL,
            document TEXT NOT NULL, PRIMARY KEY(diagram_id, revision)
        );
        CREATE TABLE IF NOT EXISTS mutations(
            diagram_id TEXT NOT NULL REFERENCES diagrams(id),
            mutation_key TEXT NOT NULL, request_hash TEXT NOT NULL,
            result_revision INTEGER NOT NULL,
            PRIMARY KEY(diagram_id, mutation_key)
        );
        CREATE TABLE IF NOT EXISTS proposals(
            diagram_id TEXT NOT NULL REFERENCES diagrams(id),
            proposal_id TEXT NOT NULL, base_revision INTEGER NOT NULL,
            document TEXT NOT NULL, summary TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending', created_at TEXT NOT NULL,
            resolved_at TEXT, accepted_revision INTEGER,
            PRIMARY KEY(diagram_id, proposal_id)
        );
        CREATE INDEX IF NOT EXISTS diagram_list ON diagrams(archived, updated_at DESC, id DESC);
        """)


@asynccontextmanager
async def lifespan(_: FastAPI):
    initialize()
    yield


app = FastAPI(title="OmniDesign", version="1.0", lifespan=lifespan)


async def body_json(request: Request) -> dict:
    # Chunked requests must be checked too; Content-Length is not trusted.
    chunks = []
    total = 0
    async for part in request.stream():
        total += len(part)
        if total > LIMIT:
            problem(413, "Request exceeds 8 MiB")
        chunks.append(part)
    try:
        data = json.loads(b"".join(chunks))
    except (ValueError, UnicodeError):
        problem(422, "Invalid JSON")
    if not isinstance(data, dict):
        problem(422, "Expected JSON object")
    return data


def required_id(value: str) -> str:
    if not ID.fullmatch(value):
        problem(404, "Unknown diagram")
    return value


def current(db: sqlite3.Connection, diagram_id: str) -> sqlite3.Row:
    row = db.execute("SELECT * FROM diagrams WHERE id=?", (required_id(diagram_id),)).fetchone()
    if row is None:
        problem(404, "Diagram not found")
    return row


def summary(row: sqlite3.Row) -> dict:
    return {"id": row["id"], "title": row["title"], "family": row["family"],
            "revision": row["revision"], "description": row["description"],
            "tags": json.loads(row["tags"]), "createdAt": row["created_at"],
            "updatedAt": row["updated_at"], "archived": bool(row["archived"])}


def document_response(doc: dict, status: int = 200) -> JSONResponse:
    return JSONResponse(doc, status_code=status, headers={"ETag": '"rev-%d"' % doc["revision"]})


def store_revision(db: sqlite3.Connection, doc: dict, saved_at: str) -> None:
    blob = serialized(doc)
    db.execute("INSERT INTO revisions VALUES(?,?,?,?)", (doc["id"], doc["revision"], saved_at, blob))
    db.execute("""UPDATE diagrams SET revision=?,title=?,family=?,description=?,tags=?,
                  updated_at=?,document=? WHERE id=?""",
               (doc["revision"], doc["title"], doc["family"],
                doc["metadata"].get("description", ""), serialized(doc["metadata"].get("tags", [])),
                saved_at, blob, doc["id"]))


def condition(match: str | None) -> int:
    if match is None:
        problem(428, 'If-Match: "rev-N" required')
    found = re.fullmatch(r'"rev-(\d+)"', match)
    if not found:
        problem(428, 'Strong If-Match: "rev-N" required')
    return int(found.group(1))


def stale(row: sqlite3.Row) -> None:
    problem(412, "Stale revision", currentRevision=row["revision"], etag='"rev-%d"' % row["revision"])


@app.get("/api/health")
def health():
    with connection() as db:
        db.execute("SELECT 1").fetchone()
    return {"service": "omnidesign", "status": "ok", "schemaVersion": 1}


@app.get("/api/v1/diagrams")
def list_diagrams(cursor: str | None = None, limit: int = Query(50, ge=1, le=100),
                  includeArchived: bool = False):
    since = None
    if cursor:
        try:
            since = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
            if not isinstance(since, list) or len(since) != 2 or not all(isinstance(v, str) for v in since):
                raise ValueError
        except (ValueError, TypeError):
            problem(422, "Invalid page cursor")
    where = "WHERE (? = 1 OR archived = 0)"
    args: list[Any] = [int(includeArchived)]
    if since:
        where += " AND (updated_at,id) < (?,?)"
        args.extend(since)
    with connection() as db:
        rows = db.execute("SELECT * FROM diagrams " + where + " ORDER BY updated_at DESC,id DESC LIMIT ?",
                          (*args, limit + 1)).fetchall()
    page = rows[:limit]
    next_cursor = None
    if len(rows) > limit and page:
        last = page[-1]
        token = json.dumps([last["updated_at"], last["id"]]).encode()
        next_cursor = base64.urlsafe_b64encode(token).decode().rstrip("=")
    return {"items": [summary(row) for row in page], "nextCursor": next_cursor}


@app.post("/api/v1/diagrams", status_code=201)
async def create_diagram(request: Request):
    incoming = validated(await body_json(request), revision=0)
    fresh = {**incoming, "revision": 1}
    timestamp = now()
    with connection(writing=True) as db:
        old = db.execute("SELECT document,revision FROM diagrams WHERE id=?", (incoming["id"],)).fetchone()
        if old:
            # Retried POST after a lost response: only the exact first-created payload is idempotent.
            if old["revision"] == 1 and serialized(json.loads(old["document"])) == serialized(fresh):
                return document_response(fresh, 200)
            problem(409, "Diagram ID already exists with different content")
        meta = fresh["metadata"]
        db.execute("""INSERT INTO diagrams
            (id,revision,title,family,description,tags,created_at,updated_at,archived,document)
            VALUES(?,?,?,?,?,?,?,?,0,?)""",
            (fresh["id"], 1, fresh["title"], fresh["family"], meta.get("description", ""),
             serialized(meta.get("tags", [])), meta["createdAt"], timestamp, serialized(fresh)))
        db.execute("INSERT INTO revisions VALUES(?,?,?,?)",
                   (fresh["id"], 1, timestamp, serialized(fresh)))
    return document_response(fresh, 201)


@app.get("/api/v1/diagrams/{diagram_id}")
def get_diagram(diagram_id: str):
    with connection() as db:
        row = current(db, diagram_id)
    return document_response(json.loads(row["document"]))


@app.put("/api/v1/diagrams/{diagram_id}")
async def update_diagram(diagram_id: str, request: Request,
                         if_match: str | None = Header(None, alias="If-Match"),
                         idempotency_key: str | None = Header(None, alias="Idempotency-Key")):
    base = condition(if_match)
    if not idempotency_key or not re.fullmatch(r"[A-Za-z0-9_-]{8,128}", idempotency_key):
        problem(428, "Idempotency-Key (8-128 URL-safe characters) required")
    proposed = validated(await body_json(request), expected_id=diagram_id, revision=base)
    digest = hashlib.sha256((if_match + serialized(proposed)).encode()).hexdigest()
    with connection(writing=True) as db:
        row = current(db, diagram_id)
        receipt = db.execute("SELECT * FROM mutations WHERE diagram_id=? AND mutation_key=?",
                             (diagram_id, idempotency_key)).fetchone()
        if receipt:
            if receipt["request_hash"] != digest:
                problem(409, "Idempotency-Key reused for another write")
            saved = db.execute("SELECT document FROM revisions WHERE diagram_id=? AND revision=?",
                               (diagram_id, receipt["result_revision"])).fetchone()
            return document_response(json.loads(saved["document"]))
        if row["revision"] != base:
            stale(row)
        updated = {**proposed, "revision": base + 1}
        store_revision(db, updated, now())
        db.execute("INSERT INTO mutations VALUES(?,?,?,?)",
                   (diagram_id, idempotency_key, digest, base + 1))
    return document_response(updated)


@app.get("/api/v1/diagrams/{diagram_id}/revisions")
def list_revisions(diagram_id: str):
    with connection() as db:
        current(db, diagram_id)
        rows = db.execute("SELECT revision,saved_at FROM revisions WHERE diagram_id=? ORDER BY revision DESC",
                          (diagram_id,)).fetchall()
    return {"items": [{"revision": row["revision"], "savedAt": row["saved_at"]} for row in rows]}


@app.get("/api/v1/diagrams/{diagram_id}/revisions/{revision}")
def read_revision(diagram_id: str, revision: int):
    with connection() as db:
        current(db, diagram_id)
        row = db.execute("SELECT document FROM revisions WHERE diagram_id=? AND revision=?",
                         (diagram_id, revision)).fetchone()
    if row is None:
        problem(404, "Revision not found")
    return document_response(json.loads(row["document"]))


@app.post("/api/v1/diagrams/{diagram_id}/proposals", status_code=201)
async def add_proposal(diagram_id: str, request: Request):
    payload = await body_json(request)
    pid = payload.get("proposalId")
    base = payload.get("baseRevision")
    summary_text = payload.get("summary", "")
    if not isinstance(pid, str) or not PROPOSAL_ID.fullmatch(pid) or type(base) is not int or base < 1:
        problem(422, "Proposal ID/baseRevision invalid")
    if not isinstance(summary_text, str) or len(summary_text) > 2000:
        problem(422, "Proposal summary invalid")
    doc = validated(payload.get("document"), expected_id=diagram_id, revision=base)
    blob = serialized(doc)
    with connection(writing=True) as db:
        current(db, diagram_id)
        old = db.execute("SELECT * FROM proposals WHERE diagram_id=? AND proposal_id=?",
                         (diagram_id, pid)).fetchone()
        if old:
            if old["base_revision"] != base or old["document"] != blob or old["summary"] != summary_text:
                problem(409, "Proposal ID already used for different content")
            return {"proposalId": pid, "status": old["status"], "baseRevision": base}
        db.execute("""INSERT INTO proposals(diagram_id,proposal_id,base_revision,document,summary,created_at)
                      VALUES(?,?,?,?,?,?)""", (diagram_id, pid, base, blob, summary_text, now()))
    return {"proposalId": pid, "status": "pending", "baseRevision": base}


@app.get("/api/v1/diagrams/{diagram_id}/proposals")
def list_proposals(diagram_id: str):
    with connection() as db:
        current(db, diagram_id)
        rows = db.execute("""SELECT proposal_id,base_revision,summary,status,created_at,
                             resolved_at,accepted_revision FROM proposals WHERE diagram_id=?
                             ORDER BY created_at DESC""", (diagram_id,)).fetchall()
    return {"items": [dict(row) for row in rows]}


@app.get("/api/v1/diagrams/{diagram_id}/proposals/{proposal_id}")
def read_proposal(diagram_id: str, proposal_id: str):
    with connection() as db:
        current(db, diagram_id)
        row = db.execute("SELECT * FROM proposals WHERE diagram_id=? AND proposal_id=?",
                         (diagram_id, proposal_id)).fetchone()
    if row is None:
        problem(404, "Proposal not found")
    return {"proposalId": row["proposal_id"], "baseRevision": row["base_revision"],
            "summary": row["summary"], "status": row["status"],
            "createdAt": row["created_at"], "resolvedAt": row["resolved_at"],
            "acceptedRevision": row["accepted_revision"], "document": json.loads(row["document"])}


@app.post("/api/v1/diagrams/{diagram_id}/proposals/{proposal_id}/accept")
def accept_proposal(diagram_id: str, proposal_id: str):
    with connection(writing=True) as db:
        row = current(db, diagram_id)
        proposal = db.execute("SELECT * FROM proposals WHERE diagram_id=? AND proposal_id=?",
                              (diagram_id, proposal_id)).fetchone()
        if not proposal:
            problem(404, "Proposal not found")
        if proposal["status"] != "pending":
            problem(409, "Proposal already resolved")
        if proposal["base_revision"] != row["revision"]:
            stale(row)
        updated = {**json.loads(proposal["document"]), "revision": row["revision"] + 1}
        validated(updated, expected_id=diagram_id, revision=row["revision"] + 1)
        store_revision(db, updated, now())
        db.execute("""UPDATE proposals SET status='accepted',resolved_at=?,accepted_revision=?
                      WHERE diagram_id=? AND proposal_id=?""",
                   (now(), updated["revision"], diagram_id, proposal_id))
    return document_response(updated)


@app.post("/api/v1/diagrams/{diagram_id}/proposals/{proposal_id}/discard")
def discard_proposal(diagram_id: str, proposal_id: str):
    with connection(writing=True) as db:
        current(db, diagram_id)
        affected = db.execute("""UPDATE proposals SET status='discarded',resolved_at=?
                WHERE diagram_id=? AND proposal_id=? AND status='pending'""",
                (now(), diagram_id, proposal_id)).rowcount
        if affected != 1:
            problem(409, "No pending proposal with that ID")
    return {"proposalId": proposal_id, "status": "discarded"}


@app.post("/api/v1/diagrams/{diagram_id}/archive")
def archive_diagram(diagram_id: str):
    with connection(writing=True) as db:
        current(db, diagram_id)
        db.execute("UPDATE diagrams SET archived=1 WHERE id=?", (diagram_id,))
    return {"id": diagram_id, "archived": True}


@app.post("/api/v1/diagrams/{diagram_id}/restore")
def restore_diagram(diagram_id: str):
    with connection(writing=True) as db:
        current(db, diagram_id)
        db.execute("UPDATE diagrams SET archived=0 WHERE id=?", (diagram_id,))
    return {"id": diagram_id, "archived": False}


@app.get("/api/v1/diagrams/{diagram_id}/export")
def export_native(diagram_id: str):
    with connection() as db:
        row = current(db, diagram_id)
    return Response(row["document"], media_type="application/json",
                    headers={"Content-Disposition": 'attachment; filename="' + diagram_id + '.omd.json"'})


# The built editor and all its relative /assets paths live at the tool root.
# No new Hub routes or external login systems are needed.
if DIST.is_dir():
    app.mount("/", StaticFiles(directory=str(DIST), html=True), name="editor")
else:
    @app.get("/")
    def missing_editor():
        return JSONResponse({"service": "omnidesign", "status": "editor-not-built",
                             "action": "Run npm run build in OmniDesign/web"})
