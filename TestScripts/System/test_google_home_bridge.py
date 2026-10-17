"""Simulates Google's side of the OmniHub Google Home bridge against a running hub.

Runs the OAuth account-linking flow, then SYNC / QUERY / EXECUTE / token refresh.
EXECUTE is only sent for an unknown device id so no real macro is triggered.
Usage: python test_google_home_bridge.py [base_url] [--disconnect]   (default http://127.0.0.1:5055)
--disconnect also tests DISCONNECT, which revokes ALL tokens including Google's live link.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
TEST_DISCONNECT = "--disconnect" in sys.argv
BASE = (ARGS[0] if ARGS else "http://127.0.0.1:5055").rstrip("/")
STATE = os.path.join(os.environ["APPDATA"], "OmniSync", "google_home.json")
REDIRECT = "https://oauth-redirect.googleusercontent.com/r/omni-test-project"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


opener = urllib.request.build_opener(NoRedirect)
failures = []


def check(name, condition, detail=""):
    print(("PASS " if condition else "FAIL ") + name + (f"  ({detail})" if detail and not condition else ""))
    if not condition:
        failures.append(name)


def request(method, path, data=None, headers=None):
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers or {})
    try:
        with opener.open(req, timeout=10) as resp:
            return resp.status, dict(resp.headers), resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()


def form(path, fields):
    return request("POST", path, urllib.parse.urlencode(fields).encode(),
                   {"Content-Type": "application/x-www-form-urlencoded"})


def fulfill(token, intent, payload=None):
    body = {"requestId": "req-1", "inputs": [{"intent": intent, **({"payload": payload} if payload else {})}]}
    return request("POST", "/google-home/fulfillment", json.dumps(body).encode(),
                   {"Content-Type": "application/json", "Authorization": f"Bearer {token}"})


status, _, body = request("GET", "/google-home")
check("status endpoint", status == 200, f"{status} {body}")
print("   ", body)

status, _, _ = request("GET", "/signalrhub")
check("bridge port hides other endpoints", status == 404, str(status))

state = json.load(open(STATE, encoding="utf-8"))
client_id, client_secret = state["ClientId"], state["ClientSecret"]

status, _, _ = request("GET", "/google-home/auth?" + urllib.parse.urlencode(
    {"client_id": client_id, "redirect_uri": "https://evil.example/cb", "state": "x", "response_type": "code"}))
check("auth rejects foreign redirect", status == 400, str(status))

status, headers, _ = request("GET", "/google-home/auth?" + urllib.parse.urlencode(
    {"client_id": client_id, "redirect_uri": REDIRECT, "state": "abc", "response_type": "code"}))
location = headers.get("Location", "")
query = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
check("auth redirects with code", status == 302 and location.startswith(REDIRECT) and query.get("state") == ["abc"], location)
code = query.get("code", [""])[0]

status, _, body = form("/google-home/token", {"grant_type": "authorization_code", "code": code,
                                              "client_id": client_id, "client_secret": "wrong"})
check("token rejects wrong secret", status == 400, body)

status, _, body = form("/google-home/token", {"grant_type": "authorization_code", "code": code,
                                              "client_id": client_id, "client_secret": client_secret,
                                              "redirect_uri": REDIRECT})
tokens = json.loads(body) if status == 200 else {}
check("token exchange", status == 200 and tokens.get("access_token") and tokens.get("refresh_token"), body)

status, _, _ = fulfill("not-a-token", "action.devices.SYNC")
check("fulfillment rejects bad token", status == 401, str(status))

status, _, body = fulfill(tokens.get("access_token", ""), "action.devices.SYNC")
sync = json.loads(body) if status == 200 else {}
devices = sync.get("payload", {}).get("devices", [])
check("SYNC", status == 200 and sync.get("requestId") == "req-1" and sync["payload"].get("agentUserId"), body)
for d in devices:
    print(f"    scene: {d['name']['name']}  ({d['id']})")
if not devices:
    print("    (no macros with Category 'Voice' yet)")

ids = [{"id": d["id"]} for d in devices] + [{"id": "macro:does-not-exist"}]
status, _, body = fulfill(tokens.get("access_token", ""), "action.devices.QUERY", {"devices": ids})
query_devices = json.loads(body)["payload"]["devices"] if status == 200 else {}
check("QUERY", status == 200 and query_devices.get("macro:does-not-exist", {}).get("errorCode") == "deviceNotFound"
      and all(query_devices.get(d["id"], {}).get("online") for d in devices), body)

status, _, body = fulfill(tokens.get("access_token", ""), "action.devices.EXECUTE", {"commands": [{
    "devices": [{"id": "macro:does-not-exist"}],
    "execution": [{"command": "action.devices.commands.ActivateScene", "params": {"deactivate": False}}]}]})
commands = json.loads(body)["payload"]["commands"] if status == 200 else []
check("EXECUTE unknown scene -> deviceNotFound", status == 200 and commands and commands[0]["errorCode"] == "deviceNotFound", body)

status, _, body = form("/google-home/token", {"grant_type": "refresh_token", "refresh_token": tokens.get("refresh_token", ""),
                                              "client_id": client_id, "client_secret": client_secret})
refreshed = json.loads(body) if status == 200 else {}
check("refresh token", status == 200 and refreshed.get("access_token"), body)

if TEST_DISCONNECT:
    status, _, _ = fulfill(refreshed.get("access_token", ""), "action.devices.DISCONNECT")
    check("DISCONNECT", status == 200)
    status, _, _ = fulfill(refreshed.get("access_token", ""), "action.devices.SYNC")
    check("tokens revoked after DISCONNECT", status == 401, str(status))
else:
    print("SKIP DISCONNECT (pass --disconnect; it unlinks Google)")

print("\nALL PASSED" if not failures else f"\n{len(failures)} FAILED: {failures}")
sys.exit(1 if failures else 0)
