"""
End-to-end checks for the standalone OmniZones app. NOTE: takes over the mouse and Shift/Ctrl for ~40 seconds.

Each scenario starts OmniZones.exe with its own throwaway settings file (OMNIZONES_SETTINGS), so your real
settings are untouched, then drives real mouse drags of throwaway WinForms windows:

  snap          drag into the left zone: overlay shown mid-drag, window snaps to the zone rect
  shift_frees   same drag with Shift held (default settings): no overlay, no snap
  swap          drop B onto the occupied zone: A and B swap (A takes B's old spot)
  stack_hold    same drop while holding Ctrl: B stacks on top, A stays in the zone
  stack_default OccupiedDefault=Stack: plain drop stacks, holding Ctrl swaps
  need_shift    RequireShift=true: no snap without Shift, snap with Shift
  resize        dragging a window corner into a zone resizes it, no overlay, no snap
  simple_mode   SimpleMode: a Stack zone behaves as a plain zone and a second drop stacks on top (never swaps, never splits into rows)

Usage: python test_omnizones_e2e.py [path\\to\\OmniZones.exe] [screenshot_dir]
"""
import ctypes
import json
import os
import subprocess
import sys
import tempfile
import time
from ctypes import wintypes

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
DEFAULT_EXE = os.path.join(ROOT, "OmniZones", "src", "OmniZones.App", "bin", "Debug", "net9.0-windows", "OmniZones.exe")
TOL = 4

user32 = ctypes.WinDLL("user32")
dwmapi = ctypes.WinDLL("dwmapi")
user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
user32.FindWindowW.restype = wintypes.HWND
user32.GetForegroundWindow.restype = wintypes.HWND
user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND] + [ctypes.c_int] * 4 + [wintypes.UINT]
user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
dwmapi.DwmGetWindowAttribute.argtypes = [wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]

LEFTDOWN, LEFTUP = 0x2, 0x4
VK_SHIFT, VK_CONTROL = 0x10, 0x11
KEYUP = 0x2
SW_RESTORE = 9


class MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT),
                ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD)]


def primary_work_area():
    out = []
    proc = ctypes.WINFUNCTYPE(ctypes.c_int, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(wintypes.RECT), wintypes.LPARAM)

    def cb(hmon, _hdc, _rect, _lp):
        mi = MONITORINFO(cbSize=ctypes.sizeof(MONITORINFO))
        user32.GetMonitorInfoW(wintypes.HMONITOR(hmon), ctypes.byref(mi))
        w = mi.rcWork
        out.append((not (mi.dwFlags & 1), (w.left, w.top, w.right - w.left, w.bottom - w.top)))
        return 1

    user32.EnumDisplayMonitors(None, None, proc(cb), 0)
    return sorted(out)[0][1]


def frame(hwnd):
    r = wintypes.RECT()
    dwmapi.DwmGetWindowAttribute(hwnd, 9, ctypes.byref(r), ctypes.sizeof(r))
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def close(a, b, tol=TOL):
    return a is not None and b is not None and all(abs(p - q) <= tol for p, q in zip(a, b))


def visible_rects_of(pid):
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


def raise_window(hwnd):
    """Bring a window to the top of the topmost band so a title-bar click lands on it."""
    user32.SetWindowPos(hwnd, wintypes.HWND(-1), 0, 0, 0, 0, 0x13)   # NOMOVE | NOSIZE | NOACTIVATE
    time.sleep(0.3)


def key(vk, down):
    user32.keybd_event(vk, 0, 0 if down else KEYUP, 0)


def drag(start, end, at_end=None, hold=(), steps=40):
    """Mouse-drag start -> end. `hold` keys are pressed for the whole drag and released after the drop."""
    sx, sy = start
    ex, ey = end
    user32.SetCursorPos(sx, sy)
    time.sleep(0.15)
    user32.mouse_event(LEFTDOWN, 0, 0, 0, 0)
    try:
        for i in range(1, steps + 1):
            user32.SetCursorPos(sx + (ex - sx) * i // steps, sy + (ey - sy) * i // steps)
            time.sleep(0.02)
        for k in hold:
            key(k, True)
        time.sleep(0.5)
        return at_end() if at_end else None
    finally:
        user32.mouse_event(LEFTUP, 0, 0, 0, 0)
        time.sleep(0.2)
        for k in hold:
            key(k, False)
        time.sleep(0.6)


def zone_layout(kind_right="Zone"):
    zone = lambda zid, name, x, kind: {"id": zid, "name": name, "kind": kind, "x": x, "y": 0, "w": 0.5, "h": 1, "stack": "Rows", "apps": []}
    return [{"id": "t", "name": "Test", "key": "1", "gap": 0,
             "zones": [zone("l", "Left", 0, "Zone"), zone("r", "Right", 0.5, kind_right)], "scenes": []}]


class Scenario:
    def __init__(self, exe, **settings):
        self.dir = tempfile.mkdtemp(prefix="omnizones-e2e-")
        self.settings_path = os.path.join(self.dir, "settings.json")
        layout_kind = settings.pop("right_kind", "Zone")
        body = {"enabled": True, "activeLayoutId": "t", "layouts": zone_layout(layout_kind)}
        body.update(settings)
        with open(self.settings_path, "w") as f:
            json.dump(body, f)
        env = dict(os.environ, OMNIZONES_SETTINGS=self.settings_path)
        self.app = subprocess.Popen([exe], env=env)
        self.wins = []
        self.area = primary_work_area()
        time.sleep(1.5)

    def window(self, title, rect):
        script = ("Add-Type -AssemblyName System.Windows.Forms; $f = New-Object System.Windows.Forms.Form; "
                  f"$f.Text = '{title}'; [System.Windows.Forms.Application]::Run($f)")
        proc = subprocess.Popen(["powershell", "-NoProfile", "-Command", script], creationflags=0x08000000)  # CREATE_NO_WINDOW
        hwnd = None
        for _ in range(150):
            hwnd = user32.FindWindowW(None, title)
            if hwnd:
                break
            time.sleep(0.1)
        assert hwnd, f"window {title} never appeared"
        user32.ShowWindow(hwnd, SW_RESTORE)
        user32.SetWindowPos(hwnd, wintypes.HWND(-1), *rect, 0)   # topmost so nothing covers our click points
        self.wins.append((proc, hwnd))
        time.sleep(0.4)
        return hwnd

    def zone_rect(self, which):
        x, y, w, h = self.area
        return (x if which == "l" else x + w // 2, y, w // 2, h)

    def zone_center(self, which):
        x, y, w, h = self.zone_rect(which)
        return x + w // 2, y + h // 2

    def title_point(self, hwnd):
        x, y, w, _ = frame(hwnd)
        return x + w // 2, y + 12

    def overlay_visible(self):
        return len(visible_rects_of(self.app.pid)) > 0

    def close(self):
        for proc, _ in self.wins:
            proc.kill()
        self.app.kill()
        log = os.path.join(self.dir, "omnizones.log")
        if os.path.exists(log):
            lines = open(log, encoding="utf-8").read().strip().splitlines()
            print("    app log:\n      " + "\n      ".join(lines))


results = []


def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")


def mid_state(sc, hwnd):
    return lambda: {"overlay": sc.overlay_visible(), "fg": user32.GetForegroundWindow() == hwnd}


def scenario_default(exe):
    sc = Scenario(exe)
    try:
        a = sc.window("OZ-A", (300, 300, 600, 400))
        b = sc.window("OZ-B", (2300, 300, 600, 400))
        a_start = frame(a)

        mid = drag(sc.title_point(a), sc.zone_center("l"), mid_state(sc, a))
        check("snap", close(frame(a), sc.zone_rect("l")) and mid["overlay"] and mid["fg"],
              f"frame={frame(a)} target={sc.zone_rect('l')} overlay={mid['overlay']} fg_ok={mid['fg']}")

        # swap: B (floating at b_start) dropped on the occupied left zone
        b_start = frame(b)
        drag(sc.title_point(b), sc.zone_center("l"))
        check("swap", close(frame(b), sc.zone_rect("l")) and close(frame(a), b_start, 8),
              f"B={frame(b)} A={frame(a)} expected_A_near={b_start}")

        # shift frees: drag B out to the right half with Shift held -> floats
        user32.SetWindowPos(a, wintypes.HWND(-1), *a_start, 0)
        user32.SetWindowPos(b, wintypes.HWND(-1), 2300, 300, 600, 400, 0)
        time.sleep(0.4)
        before = frame(b)
        mid = drag(sc.title_point(b), sc.zone_center("r"), mid_state(sc, b), hold=(VK_SHIFT,))
        got = frame(b)
        check("shift_frees", not close(got, sc.zone_rect("r")) and got[2:] == before[2:] and not mid["overlay"],
              f"frame={got} size_before={before[2:]} overlay={mid['overlay']}")
    finally:
        sc.close()


def scenario_stack_hold(exe):
    sc = Scenario(exe)
    try:
        a = sc.window("OZ-A", (300, 300, 600, 400))
        b = sc.window("OZ-B", (2300, 300, 600, 400))
        drag(sc.title_point(a), sc.zone_center("l"))
        raise_window(a)
        drag(sc.title_point(b), sc.zone_center("l"), hold=(VK_CONTROL,))
        check("stack_hold", close(frame(a), sc.zone_rect("l")) and close(frame(b), sc.zone_rect("l")),
              f"A={frame(a)} B={frame(b)} zone={sc.zone_rect('l')}")
    finally:
        sc.close()


def scenario_stack_default(exe):
    sc = Scenario(exe, occupiedDefault="Stack")
    try:
        a = sc.window("OZ-A", (300, 300, 600, 400))
        b = sc.window("OZ-B", (2300, 300, 600, 400))
        drag(sc.title_point(a), sc.zone_center("l"))
        drag(sc.title_point(b), sc.zone_center("l"))
        stacked = close(frame(a), sc.zone_rect("l")) and close(frame(b), sc.zone_rect("l"))
        check("stack_default", stacked, f"A={frame(a)} B={frame(b)}")

        # Ctrl inverts -> swap. Put a third window in the right zone first.
        c = sc.window("OZ-C", (300, 650, 600, 300))
        drag(sc.title_point(c), sc.zone_center("r"))
        before_c = frame(c)
        raise_window(b)
        drag(sc.title_point(b), sc.zone_center("r"), hold=(VK_CONTROL,))
        check("hold_inverts_to_swap", close(frame(b), sc.zone_rect("r")) and not close(frame(c), sc.zone_rect("r")),
              f"B={frame(b)} C={frame(c)} (C was {before_c})")
    finally:
        sc.close()


def scenario_need_shift(exe):
    sc = Scenario(exe, requireShift=True)
    try:
        a = sc.window("OZ-A", (300, 300, 600, 400))
        before = frame(a)
        mid = drag(sc.title_point(a), sc.zone_center("l"), mid_state(sc, a))
        check("need_shift_without", not close(frame(a), sc.zone_rect("l")) and not mid["overlay"],
              f"frame={frame(a)} overlay={mid['overlay']}")
        user32.SetWindowPos(a, wintypes.HWND(-1), 300, 300, 600, 400, 0)
        time.sleep(0.4)
        mid = drag(sc.title_point(a), sc.zone_center("l"), mid_state(sc, a), hold=(VK_SHIFT,))
        check("need_shift_with", close(frame(a), sc.zone_rect("l")) and mid["overlay"],
              f"frame={frame(a)} overlay={mid['overlay']} fg_ok={mid['fg']}")
    finally:
        sc.close()


def scenario_resize(exe):
    sc = Scenario(exe)
    try:
        a = sc.window("OZ-A", (300, 300, 600, 400))
        x, y, w, h = frame(a)
        mid = drag((x + w + 3, y + h + 3), sc.zone_center("l"), mid_state(sc, a))
        got = frame(a)
        check("resize", not mid["overlay"] and not close(got, sc.zone_rect("l")) and got[2] != w,
              f"frame {(x, y, w, h)} -> {got} overlay={mid['overlay']}")
    finally:
        sc.close()


def scenario_simple_mode(exe):
    sc = Scenario(exe, simpleMode=True, right_kind="Stack")
    try:
        a = sc.window("OZ-A", (300, 300, 600, 400))
        b = sc.window("OZ-B", (300, 800, 600, 400))
        drag(sc.title_point(a), sc.zone_center("r"))
        drag(sc.title_point(b), sc.zone_center("r"))
        plain = close(frame(b), sc.zone_rect("r")) and close(frame(a), sc.zone_rect("r"))
        check("simple_mode", plain, f"A={frame(a)} B={frame(b)} (both fill the zone: stacked on top, not rows, not swapped)")
    finally:
        sc.close()


def main():
    exe = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_EXE
    assert os.path.exists(exe), f"build the app first: {exe}"
    for run in (scenario_default, scenario_stack_hold, scenario_stack_default, scenario_need_shift, scenario_resize, scenario_simple_mode):
        try:
            run(exe)
        except Exception as e:  # keep going so one broken scenario does not hide the rest
            check(run.__name__, False, f"exception: {e!r}")
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed" + (f" (failed: {failed})" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
