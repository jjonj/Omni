#!/usr/bin/env python3
"""Compatibility helper for the retired C# Hub; never kills/restarts the live Rust Hub.

The Rust Hub owns production services and builds in a separate repository.
Use --build-ui for an explicitly requested, non-deploying frontend-only build.
"""
import argparse
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

RUST_REPO = Path(r"D:\SSDProjects\Tools\Omnish\main")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-ui", action="store_true", help="Build Rust OmniHub frontend without restarting or deploying")
    args = parser.parse_args()
    if not RUST_REPO.is_dir():
        parser.error(f"Rust OmniHub repository not found: {RUST_REPO}")
    if args.build_ui:
        command = [sys.executable, str(RUST_REPO / "build.py"), "omnihub", "--ui-only"]
        return subprocess.call(command, cwd=RUST_REPO)
    print("The C# OmniSync.Hub is retired. Rust OmniHub runs from", RUST_REPO)
    for port, path in ((3333, "/gateway/"), (5000, "/signalrhub/negotiate")):
        address = f"http://127.0.0.1:{port}{path}"
        try:
            with urllib.request.urlopen(address, timeout=3) as response:
                print(f"{address}: HTTP {response.status}")
        except urllib.error.HTTPError as err:
            # Non-2xx responses still prove an HTTP listener exists.
            print(f"{address}: HTTP {err.code}")
        except (urllib.error.URLError, TimeoutError) as err:
            print(f"{address}: unreachable ({err})")
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
