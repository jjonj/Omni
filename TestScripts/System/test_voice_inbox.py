"""Checks the Hub's voice inbox endpoint (free-text commands) against a running hub.

Only sends text no handler matches, so nothing is written to the vault and no macro runs.
Usage: python test_voice_inbox.py
"""
import json
import sys
import urllib.error
import urllib.request

API_KEY = "test_api_key"
failures = []


def post(url, body, key=API_KEY):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["X-Omni-Api-Key"] = key
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def check(name, condition, detail=""):
    print(("PASS " if condition else "FAIL ") + name + ("" if condition else f"  ({detail})"))
    if not condition:
        failures.append(name)


status, body = post("http://127.0.0.1:5000/api/voice-inbox", {"text": "zz voice inbox self test"}, key=None)
check("rejects missing api key", status == 401, status)

status, body = post("http://127.0.0.1:5000/api/voice-inbox", {"text": "zz voice inbox self test", "source": "test"})
check("unmatched text is accepted and reported unhandled", status == 200 and json.loads(body)["status"] == "unhandled", body)

status, body = post("http://127.0.0.1:5000/api/voice-inbox", {"text": "  "})
check("rejects empty text", status == 400, body)

status, body = post("http://127.0.0.1:5055/api/voice-inbox", {"text": "zz voice inbox self test"})
check("not reachable on the public Google Home port", status == 404, status)

print("\nALL PASSED" if not failures else f"\n{len(failures)} FAILED: {failures}")
sys.exit(1 if failures else 0)
