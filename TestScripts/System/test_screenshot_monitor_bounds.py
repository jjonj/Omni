#!/usr/bin/env python3
import ctypes
import json
import os
import struct
import urllib.parse
import urllib.request
from ctypes import wintypes
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
HUB_BASE_URL = os.environ.get("OMNI_HUB_URL", "http://127.0.0.1:5000").rstrip("/")
CONFIG_PATH = Path(os.environ.get("OMNIHUB_AUTH_CONFIG") or Path(os.environ["LOCALAPPDATA"]) / "OmniHub/config/appsettings.json")
PER_MONITOR_AWARE_V2 = ctypes.c_void_p(-4)


class MonitorInfoEx(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("rcMonitor", wintypes.RECT),
        ("rcWork", wintypes.RECT),
        ("dwFlags", wintypes.DWORD),
        ("szDevice", wintypes.WCHAR * 32),
    ]


def physical_monitors():
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    user32.SetThreadDpiAwarenessContext.argtypes = [ctypes.c_void_p]
    user32.SetThreadDpiAwarenessContext.restype = ctypes.c_void_p
    previous_context = user32.SetThreadDpiAwarenessContext(PER_MONITOR_AWARE_V2)
    if not previous_context:
        raise ctypes.WinError(ctypes.get_last_error())

    monitors = []
    callback_type = ctypes.WINFUNCTYPE(
        wintypes.BOOL, ctypes.c_void_p, ctypes.c_void_p,
        ctypes.POINTER(wintypes.RECT), wintypes.LPARAM
    )
    user32.GetMonitorInfoW.argtypes = [ctypes.c_void_p, ctypes.POINTER(MonitorInfoEx)]

    @callback_type
    def collect(handle, _dc, _rect, _data):
        info = MonitorInfoEx()
        info.cbSize = ctypes.sizeof(info)
        if not user32.GetMonitorInfoW(handle, ctypes.byref(info)):
            return False
        bounds = info.rcMonitor
        monitors.append((bool(info.dwFlags & 1), info.szDevice,
                         bounds.right - bounds.left, bounds.bottom - bounds.top))
        return True

    try:
        user32.EnumDisplayMonitors.argtypes = [
            ctypes.c_void_p, ctypes.c_void_p, callback_type, wintypes.LPARAM
        ]
        if not user32.EnumDisplayMonitors(None, None, collect, 0):
            raise ctypes.WinError(ctypes.get_last_error())
    finally:
        user32.SetThreadDpiAwarenessContext(previous_context)

    return sorted(monitors, key=lambda item: (not item[0], item[1]))


def jpeg_size(data):
    if data[:2] != b"\xff\xd8":
        raise AssertionError("Hub response is not a JPEG image")
    pos = 2
    while pos < len(data):
        if data[pos] != 0xFF:
            pos += 1
            continue
        marker = data[pos + 1]
        pos += 2
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        length = struct.unpack_from(">H", data, pos)[0]
        if marker in (0xC0, 0xC1, 0xC2, 0xC3):
            height, width = struct.unpack_from(">HH", data, pos + 3)
            return width, height
        pos += length
    raise AssertionError("JPEG dimensions were not found")


def main():
    if os.name != "nt":
        raise SystemExit("This monitor capture check must run on Windows.")
    settings = json.loads(CONFIG_PATH.read_text(encoding="utf-8-sig"))
    api_key = settings.get("AuthApiKey")
    if not api_key:
        raise SystemExit(f"AuthApiKey is missing from {CONFIG_PATH}")

    monitors = physical_monitors()
    if not monitors:
        raise SystemExit("Windows reported no active monitors.")

    for index, (primary, device, width, height) in enumerate(monitors):
        query = urllib.parse.urlencode({"monitor": index, "scale": 1, "quality": 50})
        request = urllib.request.Request(f"{HUB_BASE_URL}/api/screenshot?{query}")
        request.add_header("X-Omni-Api-Key", api_key)
        with urllib.request.urlopen(request, timeout=15) as response:
            actual = jpeg_size(response.read())
        expected = (width, height)
        label = "primary" if primary else "secondary"
        if actual != expected:
            raise AssertionError(
                f"{device} ({label}): Hub returned {actual[0]}x{actual[1]}, "
                f"expected physical bounds {width}x{height}"
            )
        print(f"PASS {device} ({label}): {actual[0]}x{actual[1]}")


if __name__ == "__main__":
    main()
