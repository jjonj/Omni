"""
Validates RenameAgentSession and DeleteAgentSession (AgentSessionService + RpcApiHub).

Uses a throwaway Claude transcript written under ~/.claude/projects/omni-rename-delete-test/
so no real session is touched:
  - the fake session is discovered by RequestAgentSessions
  - RenameAgentSession sets an Omni-side title (listing + detail), blank title restores the original
  - DeleteAgentSession removes the .jsonl and its sidecar folder, broadcasts "deleted",
    and the session disappears from the listing
  - DeleteAgentSession on an unknown id reports an error

Usage: python agent_session_rename_delete_test.py
Requires a running Hub (build_run_omnihub.py).
"""
import json
import os
import shutil
import sys
import time
import uuid
from datetime import datetime, timezone

from agent_inbox_state_test import HubProbe, check, failures

PROJECT_DIR = os.path.join(os.path.expanduser("~"), ".claude", "projects", "omni-rename-delete-test")


def write_fake_session():
    session_id = str(uuid.uuid4())
    os.makedirs(PROJECT_DIR, exist_ok=True)
    path = os.path.join(PROJECT_DIR, session_id + ".jsonl")
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    rows = [
        {"type": "user", "sessionId": session_id, "cwd": PROJECT_DIR, "timestamp": now,
         "message": {"role": "user", "content": "Omni rename delete probe."}},
        {"type": "assistant", "sessionId": session_id, "cwd": PROJECT_DIR, "timestamp": now,
         "message": {"role": "assistant", "content": [{"type": "text", "text": "Probe reply."}]}},
    ]
    with open(path, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row) + "\n")
    sidecar = os.path.join(PROJECT_DIR, session_id)
    os.makedirs(os.path.join(sidecar, "subagents"), exist_ok=True)
    with open(os.path.join(sidecar, "subagents", "agent.jsonl"), "w", encoding="utf-8") as f:
        f.write("{}\n")
    return session_id, path, sidecar


def find(probe, session_id, timeout=45):
    end = time.time() + timeout
    while time.time() < end:
        for s in probe.listing() or []:
            if s.get("SessionId") == session_id:
                return s
        time.sleep(3)
    return None


def main():
    probe = HubProbe()
    probe.start()
    session_id, path, sidecar = write_fake_session()
    try:
        print("Discovery")
        entry = find(probe, session_id)
        check(entry is not None, "fake Claude session is listed")
        if entry is None:
            return
        key = entry["Id"]
        original = entry["Title"]

        print("Rename")
        probe.conn.send("RenameAgentSession", [key, "Renamed by Omni test"])
        time.sleep(1)
        renamed = next((s for s in probe.listing() or [] if s["Id"] == key), None)
        check(renamed is not None and renamed["Title"] == "Renamed by Omni test", "listing shows the new title")
        detail = probe.read(key)
        check(detail is not None and detail.get("Title") == "Renamed by Omni test", "detail shows the new title")
        probe.conn.send("RenameAgentSession", [key, ""])
        time.sleep(1)
        restored = next((s for s in probe.listing() or [] if s["Id"] == key), None)
        check(restored is not None and restored["Title"] == original, f"blank title restores '{original}'")

        print("Delete")
        probe.statuses.clear()
        probe.conn.send("DeleteAgentSession", [key])
        status = probe.wait_status(lambda s: s.get("Id") == key, 15)
        check(status is not None and status.get("State") == "deleted", "'deleted' status broadcast")
        check(not os.path.exists(path), "transcript file removed")
        check(not os.path.exists(sidecar), "sidecar folder removed")
        gone = all(s["Id"] != key for s in probe.listing() or [])
        check(gone, "session no longer listed")

        print("Delete unknown")
        probe.statuses.clear()
        probe.conn.send("DeleteAgentSession", ["does-not-exist"])
        status = probe.wait_status(lambda s: s.get("Id") == "does-not-exist", 15)
        check(status is not None and status.get("State") == "error", "unknown id reports an error")
    finally:
        shutil.rmtree(PROJECT_DIR, ignore_errors=True)
        probe.conn.stop()

    print("\nFAILED: " + "; ".join(failures) if failures else "\nALL PASSED")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
