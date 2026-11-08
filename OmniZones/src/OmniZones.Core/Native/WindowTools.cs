using System.Diagnostics;
using System.Text;
using OmniZones.Engine;

namespace OmniZones.Native;

public sealed record MonitorInfo(RectD Work, RectD Full, bool Primary, string Device = "", string Identity = "");

/// <summary>Thin, defensive wrappers over the Win32 window APIs OmniZones needs.</summary>
public static class WindowTools
{
    private static readonly int OwnPid = Environment.ProcessId;

    public static (double X, double Y) CursorPos()
    {
        Win32.GetCursorPos(out var p);
        return (p.X, p.Y);
    }

    public static bool IsDown(int vk) => (Win32.GetAsyncKeyState(vk) & 0x8000) != 0;

    private static RectD ToRect(Win32.RECT r) => new(r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top);

    public static List<MonitorInfo> Monitors()
    {
        var list = new List<MonitorInfo>();
        Win32.EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr h, IntPtr _, ref Win32.RECT _, IntPtr _) =>
        {
            var mi = new Win32.MONITORINFOEX { cbSize = System.Runtime.InteropServices.Marshal.SizeOf<Win32.MONITORINFOEX>(), szDevice = "" };
            if (Win32.GetMonitorInfoEx(h, ref mi))
            {
                var display = new Win32.DISPLAY_DEVICE { cb = System.Runtime.InteropServices.Marshal.SizeOf<Win32.DISPLAY_DEVICE>() };
                var identity = Win32.EnumDisplayDevices(mi.szDevice, 0, ref display, 1) ? display.DeviceID ?? "" : "";
                list.Add(new MonitorInfo(ToRect(mi.rcWork), ToRect(mi.rcMonitor),
                    (mi.dwFlags & 1) != 0, mi.szDevice ?? "", identity));
            }
            return true;
        }, IntPtr.Zero);
        return list.OrderByDescending(m => m.Primary).ThenBy(m => m.Full.X).ToList();
    }

    public static MonitorInfo MonitorAt(double x, double y)
    {
        var all = Monitors();
        return all.FirstOrDefault(m => m.Full.Contains(x, y))
            ?? all.OrderBy(m => Math.Abs(m.Full.CenterX - x) + Math.Abs(m.Full.CenterY - y)).First();
    }

    /// <summary>Visible frame (without Win10/11's invisible resize borders).</summary>
    public static RectD? Frame(IntPtr hwnd)
    {
        if (Win32.DwmGetWindowAttribute(hwnd, Win32.DWMWA_EXTENDED_FRAME_BOUNDS, out Win32.RECT f, 16) == 0) return ToRect(f);
        return Win32.GetWindowRect(hwnd, out var r) ? ToRect(r) : null;
    }

    /// <summary>The un-maximized frame: what the window would restore to.</summary>
    public static RectD? RestoreFrame(IntPtr hwnd)
    {
        if (!Win32.IsZoomed(hwnd)) return Frame(hwnd);
        var wp = new Win32.WINDOWPLACEMENT { length = System.Runtime.InteropServices.Marshal.SizeOf<Win32.WINDOWPLACEMENT>() };
        return Win32.GetWindowPlacement(hwnd, ref wp) ? ToRect(wp.rcNormalPosition) : Frame(hwnd);
    }

    /// <summary>The top-level window under a screen point, or zero.</summary>
    public static IntPtr RootWindowAt(double x, double y)
    {
        var h = Win32.WindowFromPoint(new Win32.POINT { X = (int)x, Y = (int)y });
        return h == IntPtr.Zero ? IntPtr.Zero : Win32.GetAncestor(h, Win32.GA_ROOT);
    }

    public static bool Exists(IntPtr hwnd) => Win32.IsWindow(hwnd);
    public static bool IsMaximized(IntPtr hwnd) => Win32.IsZoomed(hwnd);
    public static IntPtr Foreground() => Win32.GetForegroundWindow();
    public static void Activate(IntPtr hwnd) => Win32.SetForegroundWindow(hwnd);

    public static string Title(IntPtr hwnd)
    {
        var sb = new StringBuilder(256);
        Win32.GetWindowText(hwnd, sb, sb.Capacity);
        return sb.ToString();
    }

    /// <summary>Lowercase exe name without extension, or "" if it cannot be read.</summary>
    public static string ProcessName(IntPtr hwnd)
    {
        try
        {
            Win32.GetWindowThreadProcessId(hwnd, out var pid);
            using var p = Process.GetProcessById((int)pid);
            return p.ProcessName.ToLowerInvariant();
        }
        catch { return ""; }
    }

    public static bool IsResizable(IntPtr hwnd) => (Win32.GetWindowLongPtr(hwnd, Win32.GWL_STYLE).ToInt64() & Win32.WS_THICKFRAME) != 0;

    /// <summary>WM_NCHITTEST at the cursor: border hit codes mean the user is resizing, not moving.</summary>
    public static bool IsResizeHit(IntPtr hwnd, double x, double y)
    {
        var lparam = (IntPtr)((((int)y & 0xFFFF) << 16) | ((int)x & 0xFFFF));
        if (Win32.SendMessageTimeout(hwnd, Win32.WM_NCHITTEST, IntPtr.Zero, lparam, Win32.SMTO_ABORTIFHUNG, 50, out var hit) == IntPtr.Zero)
            return false;
        var code = (int)hit.ToInt64();
        return code >= Win32.HTLEFT && code <= Win32.HTBOTTOMRIGHT;
    }

    /// <summary>The window currently inside the system move/size loop, or zero.</summary>
    public static IntPtr DraggedWindow()
    {
        var gti = new Win32.GUITHREADINFO { cbSize = System.Runtime.InteropServices.Marshal.SizeOf<Win32.GUITHREADINFO>() };
        return Win32.GetGUIThreadInfo(0, ref gti) && (gti.flags & Win32.GUI_INMOVESIZE) != 0 ? gti.hwndMoveSize : IntPtr.Zero;
    }

    /// <summary>Size of the outer window rect (used to detect resizing during a drag).</summary>
    public static (int W, int H) OuterSize(IntPtr hwnd)
    {
        Win32.GetWindowRect(hwnd, out var r);
        return (r.Right - r.Left, r.Bottom - r.Top);
    }

    /// <summary>
    /// Place the window so its visible frame fills <paramref name="target"/> (compensates for Win10/11's invisible borders).
    /// Windows may refuse part of a combined move+resize, so a missed position is retried as a plain move, then a plain resize.
    /// </summary>
    public static void Place(IntPtr hwnd, RectD target)
    {
        if (Win32.IsZoomed(hwnd)) Win32.ShowWindow(hwnd, Win32.SW_RESTORE);
        if (!Win32.GetWindowRect(hwnd, out var win)) return;
        var frame = Frame(hwnd) ?? ToRect(win);
        double dl = frame.X - win.Left, dt = frame.Y - win.Top, dr = win.Right - frame.Right, db = win.Bottom - frame.Bottom;
        int x = (int)Math.Round(target.X - dl), y = (int)Math.Round(target.Y - dt);
        int w = (int)Math.Round(target.W + dl + dr), h = (int)Math.Round(target.H + dt + db);

        Win32.SetWindowPos(hwnd, IntPtr.Zero, x, y, w, h, Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
        Win32.GetWindowRect(hwnd, out var now);
        if (Math.Abs(now.Left - x) > 1 || Math.Abs(now.Top - y) > 1)
            Win32.SetWindowPos(hwnd, IntPtr.Zero, x, y, 0, 0, Win32.SWP_NOSIZE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
        Win32.GetWindowRect(hwnd, out now);
        if (Math.Abs(now.Right - now.Left - w) > 1 || Math.Abs(now.Bottom - now.Top - h) > 1)
            Win32.SetWindowPos(hwnd, IntPtr.Zero, 0, 0, w, h, Win32.SWP_NOMOVE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
    }

    /// <summary>True when the window already sits where <paramref name="target"/> says (position exact, size within a few px: Windows clamps at screen edges).</summary>
    public static bool IsAt(IntPtr hwnd, RectD target)
    {
        var f = Frame(hwnd);
        return f is { } a && Math.Abs(a.X - target.X) <= 2 && Math.Abs(a.Y - target.Y) <= 2 &&
               Math.Abs(a.W - target.W) <= 8 && Math.Abs(a.H - target.H) <= 8;
    }

    /// <summary>Only move a window's top-left corner (frame coordinates).</summary>
    public static void MoveFrameTo(IntPtr hwnd, double x, double y)
    {
        if (!Win32.GetWindowRect(hwnd, out var win)) return;
        var frame = Frame(hwnd) ?? ToRect(win);
        Win32.SetWindowPos(hwnd, IntPtr.Zero, (int)Math.Round(x - (frame.X - win.Left)), (int)Math.Round(y - (frame.Y - win.Top)),
            0, 0, Win32.SWP_NOSIZE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
    }

    /// <summary>Resize only (frame width/height), keeping the window's current top-left corner.</summary>
    public static void ResizeFrame(IntPtr hwnd, double w, double h)
    {
        if (!Win32.GetWindowRect(hwnd, out var win)) return;
        var frame = Frame(hwnd) ?? ToRect(win);
        double dw = (win.Right - win.Left) - frame.W, dh = (win.Bottom - win.Top) - frame.H;
        Win32.SetWindowPos(hwnd, IntPtr.Zero, 0, 0, (int)Math.Round(w + dw), (int)Math.Round(h + dh),
            Win32.SWP_NOMOVE | Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE);
    }

    /// <summary>Bring to the front of the z-order without taking focus.</summary>
    public static void Raise(IntPtr hwnd) =>
        Win32.SetWindowPos(hwnd, IntPtr.Zero, 0, 0, 0, 0, Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOACTIVATE);

    public static void Maximize(IntPtr hwnd) => Win32.ShowWindow(hwnd, Win32.SW_MAXIMIZE);
    public static void Restore(IntPtr hwnd) => Win32.ShowWindow(hwnd, Win32.SW_RESTORE);

    /// <summary>Normal, user-facing top-level windows that could be snapped (excludes this process's own windows).</summary>
    public static List<IntPtr> EnumerateAppWindows()
    {
        var list = new List<IntPtr>();
        var shell = Win32.GetShellWindow();
        Win32.EnumWindows((hwnd, _) =>
        {
            if (hwnd != shell && IsAppWindow(hwnd)) list.Add(hwnd);
            return true;
        }, IntPtr.Zero);
        return list;
    }

    public static bool IsAppWindow(IntPtr hwnd)
    {
        if (!Win32.IsWindowVisible(hwnd) || Win32.IsIconic(hwnd)) return false;
        if (Win32.GetWindow(hwnd, Win32.GW_OWNER) != IntPtr.Zero) return false;
        var ex = Win32.GetWindowLongPtr(hwnd, Win32.GWL_EXSTYLE).ToInt64();
        if ((ex & Win32.WS_EX_TOOLWINDOW) != 0 && (ex & Win32.WS_EX_APPWINDOW) == 0) return false;
        if ((ex & Win32.WS_EX_NOACTIVATE) != 0) return false;
        if (!IsResizable(hwnd)) return false;
        if (Win32.DwmGetWindowAttribute(hwnd, Win32.DWMWA_CLOAKED, out int cloaked, 4) == 0 && cloaked != 0) return false;
        Win32.GetWindowThreadProcessId(hwnd, out var pid);
        if (pid == OwnPid) return false;
        var cls = new StringBuilder(64);
        Win32.GetClassName(hwnd, cls, cls.Capacity);
        if (cls.ToString() is "Progman" or "WorkerW" or "Shell_TrayWnd") return false;
        return Title(hwnd).Length > 0;
    }

    /// <summary>Process names of the visible app windows, for pickers in the editor.</summary>
    public static List<string> RunningAppNames() =>
        EnumerateAppWindows().Select(ProcessName).Where(n => n.Length > 0).Distinct().OrderBy(n => n).ToList();
}
