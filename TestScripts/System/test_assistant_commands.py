"""Checks the deterministic assistant command pipeline against a running Hub.

The movie command is submitted as startup backlog, so it must resolve through the
real command registry but must not launch Stremio.
"""
import json
import sys
import urllib.request

API_KEY = "test_api_key"
URL = "http://127.0.0.1:5000/api/voice-inbox"
failures = []


def post(body):
    request = urllib.request.Request(
        URL,
        data=json.dumps(body).encode(),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Omni-Api-Key": API_KEY,
        },
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status, json.loads(response.read().decode())


def check(name, condition, detail=""):
    print(("PASS " if condition else "FAIL ") + name + ("" if condition else f"  ({detail})"))
    if not condition:
        failures.append(name)


status, outcome = post({
    "text": "play the Tetris movie in one hour",
    "source": "assistant-command-test",
    "isBacklog": True,
})
check("movie phrase resolves", status == 200 and outcome["status"] == "handled", outcome)
check("deterministic command handler owns movie phrase", outcome.get("handler") == "Command", outcome)
check(
    "startup backlog policy prevents stale movie playback",
    "Skipped queued media.play_movie" in outcome.get("summary", ""),
    outcome,
)

status, outcome = post({
    "text": "zz assistant command self test",
    "source": "assistant-command-test",
})
check("unknown text remains available for future LLM fallback", outcome["status"] == "unhandled", outcome)

print("\nALL PASSED" if not failures else f"\n{len(failures)} FAILED: {failures}")
sys.exit(1 if failures else 0)
