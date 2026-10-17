"""Validate cursor REST routing, authentication, coordinate checks and visibility.

Uses AuthApiKey from the Hub's local appsettings without printing it. Desktop
clicks against real test controls are validated by test_agent_cursor.py.
"""
import json
import pathlib
import urllib.error
import urllib.request
from urllib.parse import urlencode

root = pathlib.Path(__file__).resolve().parents[2]
settings = json.loads((root / "OmniSync.Hub/src/OmniSync.Hub/appsettings.json").read_text(encoding="utf-8-sig"))
key = settings["AuthApiKey"]
base = "http://127.0.0.1:5000/api/external/agent-cursor"


def request(path="", method="GET", body=None, api_key=key):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path + "?" + urlencode({"key": api_key}),
                                 data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def check(label, value):
    if not value:
        raise AssertionError(label)
    print("PASS: " + label)


check("REST rejects an invalid API key", request(api_key="invalid-cursor-test-key")[0] == 401)
status, state = request()
check("authenticated REST status", status == 200 and "monitors" in state)
try:
    check("REST hide", request("/hide", "POST")[1]["visible"] is False)
    check("REST rejects hidden click", request("/click", "POST", {})[0] == 409)
    check("REST rejects off-monitor move", request("/move", "POST", {"x": 2147483647, "y": 2147483647})[0] == 400)
    check("REST rejects invalid button", request("/click", "POST", {"button": "middle"})[0] == 400)
    monitor = state["monitors"][0]
    point = {"x": monitor["x"] + monitor["width"] // 2, "y": monitor["y"] + monitor["height"] // 2}
    status, moved = request("/move", "POST", point)
    check("REST move shows independent cursor", status == 200 and moved["visible"] and moved["x"] == point["x"] and moved["y"] == point["y"])
finally:
    request("/hide", "POST")
