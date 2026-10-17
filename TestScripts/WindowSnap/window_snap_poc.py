"""
Window snapping prototype (FancyZones-style), standalone - no Hub needed.

Drag any resizable window by its title bar. While dragging, a faint box marks the
center trigger zone of the monitor under the cursor. Move the cursor into it and a
preview of the target rect appears; release and the window snaps to 50% width x 50%
height, centered on that monitor's work area.

Run:   python TestScripts\\WindowSnap\\window_snap_poc.py
Stop:  Ctrl+C in the console

How it works:
- Polls GetGUIThreadInfo(foreground thread) every ~15ms. GUI_INMOVESIZE + hwndMoveSize
  tell us a window is inside the system move/size loop (works for any app, no hooks).
- Move vs resize is decided by WM_NCHITTEST at drag start (border hits = resize).
- Overlays are click-through, non-activating topmost tk windows (activating anything
  mid-drag would cancel the system move loop).
"""
import ctypes
import signal
import tkinter as tk
from ctypes import wintypes

# --- Tunables -----------------------------------------------------------------
POLL_MS = 15
TRIGGER_FRACTION = 0.20      # trigger zone = this fraction of work area w/h, centered
TRIGGER_MIN_PX = 160         # ...but never smaller than this
TARGET_FRACTION = 0.50       # snapped window = this fraction of work area w/h, centered
HINT_COLOR, HINT_ALPHA = "#3a7bd5", 0.18
PREVIEW_COLOR, PREVIEW_ALPHA = "#3a7bd5", 0.35
BORDER_COLOR = "#9cc3ff"

# --- Win32 --------------------------------------------------------------------
user32 = ctypes.WinDLL("user32", use_last_error=True)
dwmapi = ctypes.WinDLL("dwmapi")

GUI_INMOVESIZE = 0x2
WM_NCHITTEST = 0x0084
HTLEFT, HTBOTTOMRIGHT = 10, 17
SMTO_ABORTIFHUNG = 0x2
GWL_STYLE, GWL_EXSTYLE = -16, -20
WS_THICKFRAME = 0x00040000
WS_EX_LAYERED, WS_EX_TRANSPARENT = 0x00080000, 0x00000020
WS_EX_TOOLWINDOW, WS_EX_NOACTIVATE = 0x00000080, 0x08000000
SWP_NOZORDER, SWP_NOACTIVATE, SWP_SHOWWINDOW = 0x0004, 0x0010, 0x0040
SW_HIDE, SW_RESTORE = 0, 9
HWND_TOPMOST = wintypes.HWND(-1)
MONITOR_DEFAULTTONEAREST = 2
GA_ROOT = 2
DWMWA_EXTENDED_FRAME_BOUNDS = 9


class GUITHREADINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("flags", wintypes.DWORD),
                ("hwndActive", wintypes.HWND), ("hwndFocus", wintypes.HWND),
                ("hwndCapture", wintypes.HWND), ("hwndMenuOwner", wintypes.HWND),
                ("hwndMoveSize", wintypes.HWND), ("hwndCaret", wintypes.HWND),
                ("rcCaret", wintypes.RECT)]


class MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT),
                ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD)]


user32.GetGUIThreadInfo.argtypes = [wintypes.DWORD, ctypes.POINTER(GUITHREADINFO)]
user32.GetCursorPos.argtypes = [ctypes.POINTER(wintypes.POINT)]
user32.MonitorFromPoint.argtypes = [wintypes.POINT, wintypes.DWORD]
user32.MonitorFromPoint.restype = wintypes.HMONITOR
user32.GetMonitorInfoW.argtypes = [wintypes.HMONITOR, ctypes.POINTER(MONITORINFO)]
user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
user32.GetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int]
user32.GetWindowLongPtrW.restype = ctypes.c_ssize_t
user32.SetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_ssize_t]
user32.SetWindowLongPtrW.restype = ctypes.c_ssize_t
user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int,
                                ctypes.c_int, ctypes.c_int, wintypes.UINT]
user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
user32.IsZoomed.argtypes = [wintypes.HWND]
user32.GetAncestor.argtypes = [wintypes.HWND, wintypes.UINT]
user32.GetAncestor.restype = wintypes.HWND
user32.SendMessageTimeoutW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM,
                                       wintypes.UINT, wintypes.UINT, ctypes.POINTER(ctypes.c_ssize_t)]
user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
dwmapi.DwmGetWindowAttribute.argtypes = [wintypes.HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]

# Physical pixels everywhere, so mixed-DPI multi-monitor setups line up.
user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # PER_MONITOR_AWARE_V2


def cursor_pos():
    pt = wintypes.POINT()
    user32.GetCursorPos(ctypes.byref(pt))
    return pt


def work_area(pt):
    mi = MONITORINFO(cbSize=ctypes.sizeof(MONITORINFO))
    user32.GetMonitorInfoW(user32.MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST), ctypes.byref(mi))
    r = mi.rcWork
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def centered(area, frac_w, frac_h, min_px=0):
    x, y, w, h = area
    cw, ch = max(int(w * frac_w), min_px), max(int(h * frac_h), min_px)
    return x + (w - cw) // 2, y + (h - ch) // 2, cw, ch


def contains(rect, pt):
    x, y, w, h = rect
    return x <= pt.x < x + w and y <= pt.y < y + h


def window_title(hwnd):
    buf = ctypes.create_unicode_buffer(256)
    user32.GetWindowTextW(hwnd, buf, 256)
    return buf.value


def is_resize_hit(hwnd, pt):
    """WM_NCHITTEST at the cursor: border codes mean the user is resizing, not moving."""
    result = ctypes.c_ssize_t(0)
    lparam = ((pt.y & 0xFFFF) << 16) | (pt.x & 0xFFFF)
    if not user32.SendMessageTimeoutW(hwnd, WM_NCHITTEST, 0, lparam, SMTO_ABORTIFHUNG, 50, ctypes.byref(result)):
        return False
    return HTLEFT <= result.value <= HTBOTTOMRIGHT


def snap_window(hwnd, rect):
    """Place hwnd so its *visible* frame fills rect (compensates for Win10/11 invisible borders)."""
    if user32.IsZoomed(hwnd):
        user32.ShowWindow(hwnd, SW_RESTORE)
    x, y, w, h = rect
    win, frame = wintypes.RECT(), wintypes.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(win))
    if dwmapi.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, ctypes.byref(frame), ctypes.sizeof(frame)) == 0:
        dl, dt = frame.left - win.left, frame.top - win.top
        dr, db = win.right - frame.right, win.bottom - frame.bottom
        x, y, w, h = x - dl, y - dt, w + dl + dr, h + dt + db
    user32.SetWindowPos(hwnd, None, x, y, w, h, SWP_NOZORDER | SWP_NOACTIVATE)


class Overlay:
    """Click-through, never-activating translucent box."""

    def __init__(self, root, color, alpha):
        self.win = tk.Toplevel(root)
        self.win.overrideredirect(True)
        self.win.attributes("-topmost", True)
        self.win.attributes("-alpha", alpha)
        self.win.configure(bg=color, highlightthickness=2, highlightbackground=BORDER_COLOR)
        self.win.geometry("1x1+-10000+-10000")
        self.win.update_idletasks()
        self.hwnd = user32.GetAncestor(self.win.winfo_id(), GA_ROOT)
        ex = user32.GetWindowLongPtrW(self.hwnd, GWL_EXSTYLE)
        user32.SetWindowLongPtrW(self.hwnd, GWL_EXSTYLE,
                                 ex | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE)
        self.rect = None
        self.visible = True
        self.hide()

    def show(self, rect):
        if rect == self.rect:
            return
        self.rect = rect
        x, y, w, h = rect
        self.win.geometry(f"{w}x{h}+{x}+{y}")
        user32.SetWindowPos(self.hwnd, HWND_TOPMOST, x, y, w, h, SWP_NOACTIVATE | SWP_SHOWWINDOW)
        self.visible = True

    def hide(self):
        if self.visible:
            user32.ShowWindow(self.hwnd, SW_HIDE)
            self.visible = False
        self.rect = None


class SnapController:
    def __init__(self, root):
        self.root = root
        self.hint = Overlay(root, HINT_COLOR, HINT_ALPHA)
        self.preview = Overlay(root, PREVIEW_COLOR, PREVIEW_ALPHA)
        self.drag_hwnd = None
        self.snappable = False
        self.target = None
        self.last_size = None
        self.size_changes = 0

    def tick(self):
        try:
            self._tick()
        finally:
            self.root.after(POLL_MS, self.tick)

    def _tick(self):
        gti = GUITHREADINFO(cbSize=ctypes.sizeof(GUITHREADINFO))
        moving = user32.GetGUIThreadInfo(0, ctypes.byref(gti)) and (gti.flags & GUI_INMOVESIZE) and gti.hwndMoveSize
        hwnd = gti.hwndMoveSize if moving else None

        if hwnd and hwnd != self.drag_hwnd:
            self._begin(hwnd)
        elif not hwnd and self.drag_hwnd:
            self._end()
            return

        if self.drag_hwnd and self.snappable and self._detect_resize():
            self.snappable = False
            self.target = None
            self.hint.hide()
            self.preview.hide()
            print(f"            hwnd={self.drag_hwnd:#x} size keeps changing -> resize, ignoring")

        if self.drag_hwnd and self.snappable:
            pt = cursor_pos()
            area = work_area(pt)
            zone = centered(area, TRIGGER_FRACTION, TRIGGER_FRACTION, TRIGGER_MIN_PX)
            if contains(zone, pt):
                self.target = centered(area, TARGET_FRACTION, TARGET_FRACTION)
                self.hint.hide()
                self.preview.show(self.target)
            else:
                self.target = None
                self.preview.hide()
                self.hint.show(zone)

    def _detect_resize(self):
        """The start-of-drag hit test can miss (we poll, so the cursor may already have left
        the border). Backstop: a move keeps the size constant, except one change when a
        maximized/Aero-snapped window restores as you drag it - so 2+ changes = resize."""
        if user32.IsZoomed(self.drag_hwnd):
            return False
        r = wintypes.RECT()
        user32.GetWindowRect(self.drag_hwnd, ctypes.byref(r))
        size = (r.right - r.left, r.bottom - r.top)
        if self.last_size is not None and size != self.last_size:
            self.size_changes += 1
        self.last_size = size
        return self.size_changes >= 2

    def _begin(self, hwnd):
        self.drag_hwnd = hwnd
        self.target = None
        self.last_size = None
        self.size_changes = 0
        style = user32.GetWindowLongPtrW(hwnd, GWL_STYLE)
        resizable = bool(style & WS_THICKFRAME)
        resizing = is_resize_hit(hwnd, cursor_pos())
        self.snappable = resizable and not resizing
        print(f"drag start  hwnd={hwnd:#x} '{window_title(hwnd)}' "
              f"{'move' if not resizing else 'resize'}{'' if resizable else ' (not resizable)'}")

    def _end(self):
        self.hint.hide()
        self.preview.hide()
        if self.snappable and self.target:
            print(f"snap        hwnd={self.drag_hwnd:#x} -> {self.target}")
            snap_window(self.drag_hwnd, self.target)
        else:
            print(f"drag end    hwnd={self.drag_hwnd:#x}")
        self.drag_hwnd = None
        self.target = None


def main():
    root = tk.Tk()
    root.withdraw()
    controller = SnapController(root)
    signal.signal(signal.SIGINT, lambda *_: root.quit())  # handled between polls
    root.after(POLL_MS, controller.tick)
    print("Window snap POC running. Drag a window to the center of a screen. Ctrl+C to quit.")
    root.mainloop()


if __name__ == "__main__":
    main()
