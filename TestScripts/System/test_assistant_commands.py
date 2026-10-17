"""System checks for the durable Jarvis command foundation against a running Hub.

All command submissions use dryRun=true, so the test never launches Stremio,
writes notes, or executes macros.
"""
import datetime
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONFIG = pathlib.Path(os.environ.get("OMNIHUB_AUTH_CONFIG") or pathlib.Path(os.environ["LOCALAPPDATA"]) / "OmniHub/config/appsettings.json")
API_KEY = json.loads(CONFIG.read_text(encoding="utf-8"))["AuthApiKey"]
BASE = "http://127.0.0.1:5000"
failures = []


def request(method, path, body=None, key=API_KEY):
    headers = {}
    if key:
        headers["X-Omni-Api-Key"] = key
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = raw
        return error.code, parsed


def check(name, condition, detail=""):
    print(("PASS " if condition else "FAIL ") + name + ("" if condition else f"  ({detail})"))
    if not condition:
        failures.append(name)


status, _ = request("GET", "/api/assistant/commands", key=None)
check("assistant catalogue requires auth", status == 401, status)

status, catalogue = request("GET", "/api/assistant/commands")
active_ids = {item["id"] for item in catalogue.get("active", [])} if status == 200 else set()
check("catalogue endpoint responds", status == 200, catalogue)
check("media.play_movie registered", "media.play_movie" in active_ids, active_ids)
check("note.add registered", "note.add" in active_ids, active_ids)

request_id = str(uuid.uuid4())
payload = {
    "text": "play the Tetris movie in an hour",
    "source": "system-test",
    "requestId": request_id,
    "occurredAt": datetime.datetime.now().astimezone().isoformat(),
    "dryRun": True,
}
status, first = request("POST", "/api/voice-inbox", payload)
check("dry-run request accepted", status == 200, first)
check("dry-run resolves movie command", first.get("commandId") == "media.play_movie", first)
check("dry-run returns structured state", first.get("runState") == "Resolved", first)
check("dry-run returns structured decision", first.get("decision") == "dry-run", first)

status, second = request("POST", "/api/voice-inbox", payload)
check("duplicate request accepted", status == 200, second)
check("request id is idempotent", second.get("runId") == first.get("runId"), (first, second))

status, runs = request("GET", "/api/assistant/runs")
run = next((item for item in runs if item.get("runId") == first.get("runId")), None) if status == 200 else None
check("run history endpoint responds", status == 200, runs)
check("dry-run persisted in run store", run is not None, first)
if run:
    check("relative timing persisted", run.get("modifiers", {}).get("runAt") is not None, run)
    check("movie title preserved through modifier parsing", run.get("arguments", {}).get("title") == "Tetris", run)

print("\nALL PASSED" if not failures else f"\n{len(failures)} FAILED: {failures}")
sys.exit(1 if failures else 0)
