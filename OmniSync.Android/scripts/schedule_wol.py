#!/usr/bin/env python3
"""Schedule a PC wake on the *Android phone*, through the authenticated OmniHub.

Requires the phone online when accepting the request. After acknowledgement the
alarm is owned by Android and fires even if this desktop/Hub goes offline.

Examples:
  python OmniSync.Android/scripts/schedule_wol.py --after 4h
  python OmniSync.Android/scripts/schedule_wol.py --at 2026-11-11T08:30
  python OmniSync.Android/scripts/schedule_wol.py --now
  python OmniSync.Android/scripts/schedule_wol.py --list
"""
import argparse
import datetime as dt
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request
import uuid

HUB = "http://127.0.0.1:5000"


def key_from_config() -> str:
    override = os.environ.get("OMNIHUB_AUTH_API_KEY")
    if override:
        return override
    config_file = os.environ.get("OMNIHUB_AUTH_CONFIG") or (
        pathlib.Path(os.environ["LOCALAPPDATA"]) / "OmniHub" / "config" / "appsettings.json"
    )
    with open(config_file, encoding="utf-8-sig") as stream:
        return json.load(stream)["AuthApiKey"]


def request(method: str, path: str, key: str, body=None):
    payload = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        HUB + path, data=payload, method=method,
        headers={"x-omni-api-key": key, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=12) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        message = error.read(4096).decode("utf-8", "replace")
        raise RuntimeError(f"Hub returned HTTP {error.code}: {message}") from error


def seconds_from_duration(value: str) -> int:
    match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(s|sec|m|min|h|hr|d|day)s?\s*", value, re.I)
    if not match:
        raise ValueError("Use durations like 30m, 4h, 1.5h, 2d")
    scale = {"s": 1, "sec": 1, "m": 60, "min": 60, "h": 3600, "hr": 3600,
             "d": 86400, "day": 86400}[match.group(2).lower()]
    return round(float(match.group(1)) * scale)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--after", metavar="DURATION", help="30m, 4h, 2d ...")
    action.add_argument("--at", metavar="ISO_LOCAL_TIME", help="2026-11-11T08:30 or ISO offset")
    action.add_argument("--now", action="store_true")
    action.add_argument("--list", action="store_true")
    parser.add_argument("--request-id", help="Stable UUID to reconcile an uncertain prior request")
    args = parser.parse_args()
    key = key_from_config()
    if args.list:
        runs = request("GET", "/api/assistant/runs", key)
        print(json.dumps([r for r in runs if r.get("commandId") == "wake.pc"], indent=2))
        return 0

    catalogue = request("GET", "/api/assistant/commands", key)
    if not any(x.get("id") == "wake.pc" for x in catalogue.get("active", [])):
        raise RuntimeError(
            "No connected Android phone advertises wake.pc. Open Omni on the phone, "
            "connect to the Hub, then retry. Nothing has been scheduled."
        )

    current = dt.datetime.now(dt.timezone.utc)
    if args.now:
        seconds = 0
    elif args.after:
        seconds = seconds_from_duration(args.after)
    else:
        at = dt.datetime.fromisoformat(args.at)
        if at.tzinfo is None:
            at = at.astimezone()  # PC's local time zone.
        seconds = int((at.astimezone(dt.timezone.utc) - current).total_seconds())

    if not args.now and (seconds < 5 or seconds > 365 * 24 * 3600):
        raise ValueError("Schedule must be between 5 seconds and 365 days in the future.")

    text = "wake my PC" if args.now else f"wake my PC in {seconds} seconds"
    request_id = args.request_id or str(uuid.uuid4())
    result = request("POST", "/api/voice-inbox", key, {
        "text": text, "source": "api", "requestId": request_id,
        "occurredAt": current.isoformat().replace("+00:00", "Z"),
    })
    print(json.dumps({"requestId": request_id, "requested": text, "result": result}, indent=2))
    if result.get("commandId") != "wake.pc" or result.get("runState") in (
        "Failed", "Skipped", "NeedsClarification"
    ):
        raise RuntimeError("Hub did not accept the Android WOL schedule.")
    print("Check --list for state Scheduled (Android acknowledgement), not just Running.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
