"""Compatibility entry point for the Android wireless ADB helper."""

import importlib.util
from pathlib import Path
import sys


SCRIPT_PATH = Path(__file__).resolve().parent / "OmniSync.Android" / "connectadb.py"
SPEC = importlib.util.spec_from_file_location("omnisync_android_connectadb", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise ImportError(f"Could not load {SCRIPT_PATH}")

MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

connect = MODULE.connect
list_devices = MODULE.list_devices
parse_mdns_services = MODULE.parse_mdns_services
resolve_endpoint = MODULE.resolve_endpoint
ADB = MODULE.ADB


if __name__ == "__main__":
    raise SystemExit(MODULE.main(sys.argv[1:]))
