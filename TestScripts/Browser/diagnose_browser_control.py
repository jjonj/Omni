#!/usr/bin/env python3
"""Non-invasive browser bridge diagnostic for the Rust OmniHub migration."""
from pathlib import Path
import json
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
RUST = Path(r"D:\SSDProjects\Tools\Omnish\main")
CHECKS = (
    (ROOT / "OmniSync.Chrome" / "background.js", "Chrome extension SignalR client"),
    (ROOT / "OmniSync.Chrome" / "manifest.json", "Chrome manifest"),
    (RUST / "src-omnihub-tauri" / "src" / "main.rs", "Rust Hub SignalR handler"),
)
failed = False
for path, name in CHECKS:
    good = path.is_file()
    print(("PASS" if good else "FAIL") + f": {name}: {path}")
    failed |= not good
url = "http://127.0.0.1:5000/signalrhub/negotiate"
try:
    request = urllib.request.Request(url, data=b"{}", method="POST")
    with urllib.request.urlopen(request, timeout=3) as response:
        result = json.load(response)
        ok = response.status == 200 and isinstance(result, dict)
        print(("PASS" if ok else "FAIL") + f": Rust Hub SignalR negotiation ({response.status})")
        failed |= not ok
except (urllib.error.URLError, TimeoutError, ValueError) as exc:
    print(f"FAIL: Rust Hub SignalR negotiation: {exc}")
    failed = True
raise SystemExit(1 if failed else 0)
