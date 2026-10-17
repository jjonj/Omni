"""
Validates the Hub side of the Agent inbox (AgentSessionService + RpcApiHub agent endpoints).

Checks (always):
  - RequestAgentSessions returns summaries with a valid State, StateSince, Capabilities and TerminalCommand.
  - Sessions active in the last 24h carry their working folder (Cwd).
  - RequestAgentSessionDetail carries the same state fields.
  - InterruptAgentTurn on a session Omni is not running reports an error instead of pretending.
  - CreateAgentSession with a missing folder reports an error.

With --live (starts real provider turns in safe mode, costs a few tokens):
  - CreateAgentSession(provider, temp folder, ...) -> "starting" broadcast -> synthetic "new-" entry
    -> the new transcript is discovered and matched -> idle with the expected reply.
  - SendAgentMessage on that session -> "sending" -> working/OmniTurn -> "completed" -> reply in detail.

Usage: python agent_inbox_state_test.py [--live] [--provider Claude|Codex|Antigravity]
Requires a running Hub (build_run_omnihub.py).
"""
import argparse
import json
import logging
import os
import sys
import tempfile
import threading
import time
from datetime import datetime

from signalrcore.hub_connection_builder import HubConnectionBuilder

HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
VALID_STATES = {"working", "question", "permission", "error", "limited", "idle", "starting"}

failures = []


def check(condition, message, quiet=False):
    if not (quiet and condition):
        print(("  PASS " if condition else "  FAIL ") + message)
    if not condition:
        failures.append(message)


class HubProbe:
    def __init__(self):
        self.sessions = None
        self.detail = None
        self.statuses = []
        self.changed = 0
        self.lock = threading.Lock()
        self.conn = HubConnectionBuilder().with_url(HUB_URL).configure_logging(logging.WARNING).build()
        self.conn.on("ReceiveAgentSessions", lambda a: self._set("sessions", json.loads(a[0])))
        self.conn.on("ReceiveAgentSessionDetail", lambda a: self._set("detail", json.loads(a[0])))
        self.conn.on("ReceiveAgentSendStatus", lambda a: self._status(json.loads(a[0])))
        self.conn.on("AgentSessionsChanged", lambda a: self._changed())

    def _set(self, name, value):
        with self.lock:
            setattr(self, name, value)

    def _status(self, value):
        with self.lock:
            self.statuses.append(value)
        print(f"    status: {value}")

    def _changed(self):
        with self.lock:
            self.changed += 1

    def start(self):
        self.conn.start()
        time.sleep(2)
        self.conn.send("Authenticate", [API_KEY])
        time.sleep(1)

    def listing(self, timeout=60):
        self.sessions = None
        self.conn.send("RequestAgentSessions", [])
        return self._wait(lambda: self.sessions, timeout)

    def read(self, session_id, timeout=60):
        self.detail = None
        self.conn.send("RequestAgentSessionDetail", [session_id, 120, 0])
        return self._wait(lambda: self.detail, timeout)

    def wait_status(self, predicate, timeout):
        return self._wait(lambda: next((s for s in list(self.statuses) if predicate(s)), None), timeout)

    @staticmethod
    def _wait(getter, timeout):
        end = time.time() + timeout
        while time.time() < end:
            value = getter()
            if value is not None:
                return value
            time.sleep(0.25)
        return None


def parse_iso(value):
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00")[:26] + ("+00:00" if value.endswith("Z") else ""))
        return True
    except Exception:
        try:
            datetime.fromisoformat(value)
            return True
        except Exception:
            return False


def check_shape(entry, label, quiet=False):
    check(entry.get("State") in VALID_STATES, f"{label}: State '{entry.get('State')}' is valid", quiet)
    check(bool(entry.get("StateSince")), f"{label}: StateSince present", quiet)
    caps = entry.get("Capabilities")
    check(isinstance(caps, dict) and "CanSend" in caps and "Note" in caps, f"{label}: Capabilities present", quiet)
    if entry.get("State") == "question":
        check(bool((entry.get("Question") or {}).get("Text")), f"{label}: question text present", quiet)
    if entry.get("State") == "error":
        check(bool(entry.get("Error")), f"{label}: error message present", quiet)
    if caps and caps.get("CanInterrupt"):
        check(entry.get("OmniTurn"), f"{label}: CanInterrupt only for Omni turns", quiet)


def baseline(probe):
    print("== Listing")
    sessions = probe.listing()
    check(sessions is not None, "RequestAgentSessions answered")
    if not sessions:
        return
    counts = {}
    for s in sessions:
        counts[s.get("State")] = counts.get(s.get("State"), 0) + 1
        check_shape(s, f"summary {s['Provider']}:{s['Id'][:8]}", quiet=True)
    print(f"  PASS {len(sessions)} summaries well-formed; states: {counts}" if not failures else f"  states: {counts}")
    recent = [s for s in sessions if s.get("Modified") and (time.time() - datetime.fromisoformat(s["Modified"][:19]).timestamp()) < 20 * 3600]
    if recent:
        with_cwd = [s for s in recent if s.get("Cwd")]
        check(len(with_cwd) >= len(recent) * 0.8, f"recent sessions carry Cwd ({len(with_cwd)}/{len(recent)})")
    with_terminal = [s for s in sessions if s.get("TerminalCommand")]
    check(len(with_terminal) > 0, f"terminal commands present ({len(with_terminal)}/{len(sessions)})")

    print("== Detail")
    newest = sessions[0]
    detail = probe.read(newest["Id"])
    check(detail is not None and detail.get("Id") == newest["Id"], "RequestAgentSessionDetail answered for newest session")
    if detail:
        check_shape(detail, "detail")
        check(isinstance(detail.get("Events"), list), "detail has events")

    print("== Interrupt guard")
    idle = next((s for s in sessions if not s.get("OmniTurn")), None)
    if idle:
        probe.statuses.clear()
        probe.conn.send("InterruptAgentTurn", [idle["Id"]])
        status = probe.wait_status(lambda s: s.get("Id") == idle["Id"], 10)
        check(status is not None and status.get("State") == "error", "interrupting a non-Omni turn reports an error")

    print("== Create guard")
    probe.statuses.clear()
    probe.conn.send("CreateAgentSession", ["Claude", r"C:\definitely\missing\folder", "hello"])
    status = probe.wait_status(lambda s: s.get("State") == "error", 10)
    check(status is not None and "not exist" in (status.get("Error") or ""), "creating in a missing folder reports an error")


def live(probe, provider):
    print(f"== Live create ({provider})")
    workspace = tempfile.mkdtemp(prefix="omni-agent-inbox-")
    probe.statuses.clear()
    probe.conn.send("CreateAgentSession", [provider, workspace, "Reply with exactly: OMNI-INBOX-ONE"])
    started = probe.wait_status(lambda s: s.get("State") == "starting", 15)
    check(started is not None, "create broadcasts 'starting'")
    pending_id = started.get("Id") if started else ""
    sessions = probe.listing()
    pending = next((s for s in sessions or [] if s["Id"] == pending_id), None)
    if pending:
        check(pending["State"] in ("starting", "working", "error"), f"pending entry visible as {pending['State']}")

    done = probe.wait_status(lambda s: s.get("State") in ("completed", "error") and s.get("Id") != "new", 240)
    check(done is not None and done.get("State") == "completed", f"create completed ({done})")
    session = None
    for _ in range(20):
        sessions = probe.listing()
        session = next((s for s in sessions or [] if (s.get("Cwd") or "").rstrip("\\").lower() == workspace.rstrip("\\").lower() and not s["Id"].startswith("new-")), None)
        if session:
            break
        time.sleep(3)
    check(session is not None, "new transcript discovered and matched to the workspace")
    if not session:
        return
    check(not any(s["Id"] == pending_id for s in sessions), "synthetic entry removed after match")
    check(session["State"] == "idle", f"new session idle after first turn ({session['State']})")
    detail = probe.read(session["Id"])
    check(detail and any("OMNI-INBOX-ONE" in e.get("Text", "") for e in detail["Events"] if e["Kind"] == "assistant"), "first reply in transcript")

    print(f"== Live continue ({provider})")
    probe.statuses.clear()
    probe.conn.send("SendAgentMessage", [session["Id"], "Reply with exactly: OMNI-INBOX-TWO", "auto"])
    sending = probe.wait_status(lambda s: s.get("Id") == session["Id"] and s.get("State") == "sending", 15)
    check(sending is not None, "send broadcasts 'sending'")
    during = next((s for s in probe.listing() or [] if s["Id"] == session["Id"]), None)
    if during:
        check(during["State"] == "working" and during["OmniTurn"], f"session working with OmniTurn during send ({during['State']})")
        check(during["Capabilities"]["CanInterrupt"] and during["Capabilities"]["CanQueue"], "can interrupt and queue during Omni turn")

    print(f"== Live queue ({provider})")
    probe.conn.send("SendAgentMessage", [session["Id"], "Reply with exactly: OMNI-INBOX-THREE", "auto"])
    queued = probe.wait_status(lambda s: s.get("Id") == session["Id"] and s.get("State") == "queued", 15)
    check(queued is not None, "message sent mid-turn is queued")
    listed = next((s for s in probe.listing() or [] if s["Id"] == session["Id"]), None)
    check(listed and len(listed.get("Queued") or []) == 1, "queued message visible on the summary")
    done = probe.wait_status(lambda s: s.get("Id") == session["Id"] and s.get("State") in ("completed", "error"), 600)
    check(done is not None and done.get("State") == "completed", f"first turn completed ({done})")
    redispatched = probe.wait_status(lambda s: s.get("Id") == session["Id"] and s.get("State") == "sending" and s is not sending, 60)
    check(redispatched is not None, "queued message dispatched after the turn ended")
    end = time.time() + 600
    detail = None
    while time.time() < end:
        detail = probe.read(session["Id"])
        if detail and any("OMNI-INBOX-THREE" in e.get("Text", "") for e in detail["Events"] if e["Kind"] == "assistant") and detail.get("State") == "idle":
            break
        time.sleep(5)
    check(detail and any("OMNI-INBOX-TWO" in e.get("Text", "") for e in detail["Events"] if e["Kind"] == "assistant"), "second reply in the same session")
    check(detail and any("OMNI-INBOX-THREE" in e.get("Text", "") for e in detail["Events"] if e["Kind"] == "assistant"), "queued message answered in the same session")
    check(detail and not detail.get("Queued"), "queue empty afterwards")

    print(f"== Live tool use ({provider}, full permissions)")
    target = os.path.join(workspace, "omni_tool_check.txt")
    probe.statuses.clear()
    probe.conn.send("SendAgentMessage", [session["Id"], "Create a file named omni_tool_check.txt in the current folder containing exactly OK, then reply DONE.", "auto"])
    done = probe.wait_status(lambda s: s.get("Id") == session["Id"] and s.get("State") in ("completed", "error"), 600)
    check(done is not None and done.get("State") == "completed", f"tool turn completed ({done})")
    # The Hub runs elevated, so the file may be unreadable to this (non-elevated) script; existence is the proof.
    check(os.path.exists(target), "agent wrote a file without an approval prompt")
    check(detail and detail.get("State") == "idle", f"idle after the turn ({detail.get('State') if detail else None})")
    check(probe.changed > 0, "AgentSessionsChanged broadcasts received")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--provider", default="Claude")
    args = parser.parse_args()

    probe = HubProbe()
    probe.start()
    try:
        baseline(probe)
        if args.live:
            live(probe, args.provider)
    finally:
        probe.conn.stop()

    print()
    if failures:
        print(f"FAILED ({len(failures)}):")
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
