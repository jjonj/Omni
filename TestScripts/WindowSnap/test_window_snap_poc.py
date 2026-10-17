"""
End-to-end checks for window_snap_poc.py. NOTE: takes over the mouse for ~10 seconds.

Starts the POC and a throwaway test window, then drives real mouse drags:
  center      - drag to primary monitor center: preview overlay shown mid-drag, window snaps
  off_center  - drop outside the trigger zone: hint overlay shown, no snap
  resize      - drag the bottom-right corner into the zone: no overlay, no snap
  maximized   - drag a maximized window to center: snaps
  secondary   - drag to the center of the second monitor (if present): snaps there
Mid-drag it also asserts the overlay never stole foreground (that would cancel the drag).

Usage: python test_window_snap_poc.py [screenshot_dir]
"""
import ctypes
import os
import struct
import subprocess
import sys
import time
import zlib
from ctypes import wintypes

HERE = os.path.dirname(os.path.abspath(__file__))
TITLE = "SnapTestWin"
TOL = 3
START_RECT = (150, 150, 700, 450)  # x, y, w, h

user32 = ctypes.WinDLL("user32")
gdi32 = ctypes.WinDLL("gdi32")
dwmapi = ctypes.WinDLL("dwmapi")
user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
user32.FindWindowW.restype = wintypes.HWND
user32.GetForegroundWindow.restype = wintypes.HWND
user32.GetDC.restype = wintypes.HDC
user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND] + [ctypes.c_int] * 4 + [wintypes.UINT]
user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
user32.ReleaseDC.argtypes = [wintypes.HWND, wintypes.HDC]
gdi32.CreateCompatibleDC.argtypes = [wintypes.HDC]
gdi32.CreateCompatibleDC.restype = wintypes.HDC
gdi32.CreateCompatibleBitmap.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.c_int]
gdi32.CreateCompatibleBitmap.restype = wintypes.HBITMAP
gdi32.DeleteObject.argtypes = [wintypes.HGDIOBJ]
gdi32.DeleteDC.argtypes = [wintypes.HDC]
gdi32.SelectObject.argtypes = [wintypes.HDC, wintypes.HGDIOBJ]
gdi32.BitBlt.argtypes = [wintypes.HDC] + [ctypes.c_int] * 4 + [wintypes.HDC, ctypes.c_int, ctypes.c_int, wintypes.DWORD]
gdi32.GetDIBits.argtypes = [wintypes.HDC, wintypes.HBITMAP, wintypes.UINT, wintypes.UINT, ctypes.c_void_p, ctypes.c_void_p, wintypes.UINT]
dwmapi.DwmGetWindowAttribute.argtypes = [wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]

LEFTDOWN, LEFTUP = 0x2, 0x4
SW_RESTORE, SW_MAXIMIZE = 9, 3


class MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT),
                ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD)]


def monitors():
    """Work areas as (x, y, w, h), primary first."""
    out = []
    proc = ctypes.WINFUNCTYPE(ctypes.c_int, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(wintypes.RECT), wintypes.LPARAM)

    def cb(hmon, _hdc, _rect, _lp):
        mi = MONITORINFO(cbSize=ctypes.sizeof(MONITORINFO))
        user32.GetMonitorInfoW(wintypes.HMONITOR(hmon), ctypes.byref(mi))
        w = mi.rcWork
        out.append((not (mi.dwFlags & 1), (w.left, w.top, w.right - w.left, w.bottom - w.top)))
        return 1

    user32.EnumDisplayMonitors(None, None, proc(cb), 0)
    return [r for _, r in sorted(out)]


def centered(area, frac, min_px=0):
    x, y, w, h = area
    cw, ch = max(int(w * frac), min_px), max(int(h * frac), min_px)
    return x + (w - cw) // 2, y + (h - ch) // 2, cw, ch


def frame(hwnd):
    r = wintypes.RECT()
    dwmapi.DwmGetWindowAttribute(hwnd, 9, ctypes.byref(r), ctypes.sizeof(r))
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def close(a, b):
    return a is not None and b is not None and all(abs(p - q) <= TOL for p, q in zip(a, b))


def visible_windows_of(pid):
    rects = []
    proc = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)

    def cb(hwnd, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid and user32.IsWindowVisible(hwnd):
            r = wintypes.RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(r))
            rects.append((r.left, r.top, r.right - r.left, r.bottom - r.top))
        return True

    user32.EnumWindows(proc(cb), 0)
    return rects


def screenshot(area, path, scale=4):
    """Grab a screen region (incl. layered windows) and save a downscaled PNG, no PIL needed."""
    x, y, w, h = area
    sdc = user32.GetDC(None)
    mdc = gdi32.CreateCompatibleDC(sdc)
    bmp = gdi32.CreateCompatibleBitmap(sdc, w, h)
    gdi32.SelectObject(mdc, bmp)
    gdi32.BitBlt(mdc, 0, 0, w, h, sdc, x, y, 0x00CC0020 | 0x40000000)  # SRCCOPY | CAPTUREBLT
    hdr = struct.pack("<IiiHHIIiiII", 40, w, -h, 1, 32, 0, 0, 0, 0, 0, 0)
    bmi = ctypes.create_string_buffer(hdr, 44)
    buf = ctypes.create_string_buffer(w * h * 4)
    gdi32.GetDIBits(mdc, bmp, 0, h, buf, bmi, 0)
    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(mdc)
    user32.ReleaseDC(None, sdc)

    raw, stride = buf.raw, w * 4
    ow = len(range(0, w, scale))
    rows = []
    for ry in range(0, h, scale):
        row = raw[ry * stride:(ry + 1) * stride]
        px = bytearray(ow * 3)
        px[0::3], px[1::3], px[2::3] = row[2::4 * scale], row[1::4 * scale], row[0::4 * scale]
        rows.append(b"\x00" + bytes(px))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", ow, len(rows), 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"".join(rows), 6)) + chunk(b"IEND", b""))


def drag(start, end, at_end=None, steps=40):
    sx, sy = start
    ex, ey = end
    user32.SetCursorPos(sx, sy)
    time.sleep(0.15)
    user32.mouse_event(LEFTDOWN, 0, 0, 0, 0)
    try:
        for i in range(1, steps + 1):
            user32.SetCursorPos(sx + (ex - sx) * i // steps, sy + (ey - sy) * i // steps)
            time.sleep(0.02)
        time.sleep(0.25)
        return at_end() if at_end else None
    finally:
        user32.mouse_event(LEFTUP, 0, 0, 0, 0)
        time.sleep(0.5)


class Runner:
    def __init__(self, hwnd, poc_pid, shot_dir):
        self.hwnd, self.poc_pid, self.shot_dir = hwnd, poc_pid, shot_dir
        self.results = []

    def reset(self):
        user32.ShowWindow(self.hwnd, SW_RESTORE)
        # Topmost so nothing (e.g. the terminal running this) covers the points we click.
        user32.SetWindowPos(self.hwnd, wintypes.HWND(-1), *START_RECT, 0)
        user32.SetForegroundWindow(self.hwnd)
        time.sleep(0.4)

    def title_point(self):
        x, y, w, _ = frame(self.hwnd)
        return x + w // 2, y + 12

    def mid_drag_state(self, name, area):
        def capture():
            state = {"overlays": visible_windows_of(self.poc_pid),
                     "foreground_ok": user32.GetForegroundWindow() == self.hwnd}
            if self.shot_dir:
                screenshot(area, os.path.join(self.shot_dir, f"{name}.png"))
            return state
        return capture

    def check(self, name, ok, detail):
        self.results.append((name, ok))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")

    def snap_to_center(self, name, area, maximize=False):
        self.reset()
        if maximize:
            user32.ShowWindow(self.hwnd, SW_MAXIMIZE)
            time.sleep(0.5)
        target = centered(area, 0.5)
        center = (area[0] + area[2] // 2, area[1] + area[3] // 2)
        mid = drag(self.title_point(), center, self.mid_drag_state(name, area))
        got = frame(self.hwnd)
        overlay = mid["overlays"][0] if len(mid["overlays"]) == 1 else None
        self.check(name, close(got, target) and close(overlay, target) and mid["foreground_ok"],
                   f"target={target} got={got} overlay={mid['overlays']} fg_ok={mid['foreground_ok']}")

    def off_center(self, area):
        self.reset()
        before = frame(self.hwnd)
        zone = centered(area, 0.20, 160)
        drop = (area[0] + area[2] // 4, area[1] + area[3] // 2)
        mid = drag(self.title_point(), drop, self.mid_drag_state("off_center", area))
        got = frame(self.hwnd)
        overlay = mid["overlays"][0] if len(mid["overlays"]) == 1 else None
        self.check("off_center", got[2:] == before[2:] and close(overlay, zone) and mid["foreground_ok"],
                   f"size {before[2:]} -> {got[2:]} hint={mid['overlays']} expected_hint={zone}")

    def resize(self, area):
        self.reset()
        x, y, w, h = frame(self.hwnd)
        corner = (x + w + 3, y + h + 3)  # inside the invisible resize border
        center = (area[0] + area[2] // 2, area[1] + area[3] // 2)
        mid = drag(corner, center, self.mid_drag_state("resize", area))
        got = frame(self.hwnd)
        self.check("resize", not mid["overlays"] and not close(got, centered(area, 0.5)) and got[2] > w,
                   f"frame {(x, y, w, h)} -> {got} overlays={mid['overlays']}")


def main():
    shot_dir = sys.argv[1] if len(sys.argv) > 1 else None
    if shot_dir:
        os.makedirs(shot_dir, exist_ok=True)
    mons = monitors()
    poc = subprocess.Popen([sys.executable, "-u", os.path.join(HERE, "window_snap_poc.py")],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    win = subprocess.Popen([sys.executable, "-c",
                            f"import tkinter as tk; r=tk.Tk(); r.title('{TITLE}'); r.mainloop()"])
    try:
        hwnd = None
        for _ in range(50):
            hwnd = user32.FindWindowW(None, TITLE)
            if hwnd:
                break
            time.sleep(0.1)
        assert hwnd, "test window never appeared"
        time.sleep(0.8)  # let the POC finish starting

        r = Runner(hwnd, poc.pid, shot_dir)
        r.snap_to_center("center", mons[0])
        r.off_center(mons[0])
        r.resize(mons[0])
        r.snap_to_center("maximized", mons[0], maximize=True)
        if len(mons) > 1:
            r.snap_to_center("secondary", mons[1])
        r.reset()

        failed = [n for n, ok in r.results if not ok]
        print(f"\n{len(r.results) - len(failed)}/{len(r.results)} passed" + (f" (failed: {failed})" if failed else ""))
        return 1 if failed else 0
    finally:
        win.kill()
        poc.kill()
        print("--- POC output ---")
        print(poc.communicate()[0])


if __name__ == "__main__":
    sys.exit(main())
