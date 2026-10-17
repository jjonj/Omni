"""
Validates that messages queued for an agent session survive a Hub restart
(AgentSessionService queue persistence, %APPDATA%\\OmniSync\\agent_queues.json).

Uses a throwaway Claude transcript under ~/.claude/projects/omni-queue-persist-test/ whose last row is a
user message written moments ago, so the session reads as "working" and a "queue" send is really queued
instead of starting a provider turn.

Two phases, with a Hub restart (build_run_omnihub.py) in between:

  python agent_queue_persistence_test.py --phase queue
      - the fake session is discovered and working
      - SendAgentMessage(mode=queue) shows up in the session's Queued list
      - agent_queues.json contains the message
  (restart the Hub)
  python agent_queue_persistence_test.py --phase verify
      - the same message (same id) is still in Queued after the restart
      - CancelQueuedAgentMessage removes it and agent_queues.json no longer has it
      - the fake transcript is deleted

Both phases must run within ~3 minutes of each other (after that the fake session stops looking busy and
the Hub would try to dispatch the message to a nonexistent provider session).

Requires a running Hub (build_run_omnihub.py).
"""
import argparse
import json
import os
import shutil
import sys
import time
import uuid
from datetime import datetime, timezone

from agent_inbox_state_test import HubProbe, check, failures

PROJECT_DIR = os.path.join(os.path.expanduser("~"), ".claude", "projects", "omni-queue-persist-test")
STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".queue_persistence_state.json")
QUEUES_FILE = os.path.join(os.environ.get("APPDATA", ""), "OmniSync", "agent_queues.json")
MESSAGE = "Omni queue persistence probe - do not send"


def write_fake_session(session_id=None):
    session_id = session_id or str(uuid.uuid4())
    os.makedirs(PROJECT_DIR, exist_ok=True)
    path = os.path.join(PROJECT_DIR, session_id + ".jsonl")
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    rows = [{"type": "user", "sessionId": session_id, "cwd": PROJECT_DIR, "timestamp": now,
             "message": {"role": "user", "content": "Omni queue persistence probe, still working."}}]
    with open(path, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row) + "\n")
    return session_id, path


def find(probe, session_id, timeout=45):
    end = time.time() + timeout
    while time.time() < end:
        for s in probe.listing() or []:
            if s.get("SessionId") == session_id:
                return s
        time.sleep(3)
    return None


def queued_ids(entry):
    return [q["Id"] for q in entry.get("Queued") or []]


def saved_queue_ids():
    if not os.path.exists(QUEUES_FILE):
        return []
    with open(QUEUES_FILE, encoding="utf-8") as f:
        data = json.load(f)
    return [m["Id"] for messages in data.values() for m in messages]


def phase_queue():
    probe = HubProbe()
    probe.start()
    session_id, path = write_fake_session()
    try:
        entry = find(probe, session_id)
        check(entry is not None, "fake Claude session is listed")
        if entry is None:
            os.remove(path)
            return
        check(entry.get("State") == "working", f"fake session reads as working (was '{entry.get('State')}')")
        key = entry["Id"]
        probe.statuses.clear()
        probe.conn.send("SendAgentMessage", [key, MESSAGE, "queue"])
        status = probe.wait_status(lambda s: s.get("Id") == key, 15)
        check(status is not None and status.get("State") == "queued", "'queued' status broadcast")
        entry = next((s for s in probe.listing() or [] if s["Id"] == key), None)
        ids = queued_ids(entry or {})
        check(len(ids) == 1, "message appears in the session's Queued list")
        check(bool(ids) and ids[0] in saved_queue_ids(), "message written to agent_queues.json")
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump({"session_id": session_id, "key": key, "queued_id": ids[0] if ids else None}, f)
        print("\nNow restart the Hub (build_run_omnihub.py) and run: --phase verify  (within ~3 minutes)")
    finally:
        probe.conn.stop()


def phase_verify():
    with open(STATE_FILE, encoding="utf-8") as f:
        state = json.load(f)
    session_id, key, queued_id = state["session_id"], state["key"], state["queued_id"]
    path = os.path.join(PROJECT_DIR, session_id + ".jsonl")
    write_fake_session(session_id)  # keep it looking busy so the restored message is not dispatched
    probe = HubProbe()
    probe.start()
    try:
        entry = find(probe, session_id)
        check(entry is not None, "fake session is listed after restart")
        if entry is None:
            return
        check(queued_id in queued_ids(entry), "queued message survived the Hub restart (same id)")
        probe.conn.send("CancelQueuedAgentMessage", [key, queued_id])
        time.sleep(2)
        entry = next((s for s in probe.listing() or [] if s["Id"] == key), None)
        check(entry is not None and queued_id not in queued_ids(entry), "cancel removes it from Queued")
        check(queued_id not in saved_queue_ids(), "cancel removes it from agent_queues.json")
    finally:
        probe.conn.stop()
        shutil.rmtree(PROJECT_DIR, ignore_errors=True)
        if os.path.exists(STATE_FILE):
            os.remove(STATE_FILE)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=["queue", "verify"], required=True)
    args = parser.parse_args()
    (phase_queue if args.phase == "queue" else phase_verify)()
    print("\nFAILED: " + "; ".join(failures) if failures else "\nALL PASSED")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
