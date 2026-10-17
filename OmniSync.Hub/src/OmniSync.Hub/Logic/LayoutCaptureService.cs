using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Services;
using OmniZones.Native;

namespace OmniSync.Hub.Logic;

public class LayoutCaptureService
{
    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr param);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr param);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hwnd, int attr, out int value, int size);

    public static List<(IntPtr Handle, string Path, string Title)> EnumerateWindows(bool includeMinimized = false)
    {
        var windows = new List<(IntPtr, string, string)>();
        EnumWindows((hwnd, _) =>
        {
            if (!IsWindowVisible(hwnd) || (!includeMinimized && IsIconic(hwnd))) return true;
            if (DwmGetWindowAttribute(hwnd, 14, out var cloaked, sizeof(int)) == 0 && cloaked != 0) return true;
            if ((!includeMinimized || !IsIconic(hwnd)) && (WindowTools.Frame(hwnd) is not { } frame || frame.W < 100 || frame.H < 80)) return true;
            var title = WindowTools.Title(hwnd);
            if (string.IsNullOrWhiteSpace(title)) return true;
            GetWindowThreadProcessId(hwnd, out var pid);
            if (pid == Environment.ProcessId) return true;
            try
            {
                using var process = Process.GetProcessById((int)pid);
                var path = process.MainModule?.FileName;
                if (!string.IsNullOrEmpty(path)) windows.Add((hwnd, path, title));
            }
            catch { }
            return true;
        }, IntPtr.Zero);
        return windows;
    }

    public List<CapturedWindow> CaptureCurrentLayout()
    {
        var captured = new List<CapturedWindow>();
        var zOrder = 0;
        foreach (var window in EnumerateWindows())
        {
            if (WindowTools.Frame(window.Handle) is not { } frame) continue;
            var path = window.Path;
            var type = ProjectActionType.RunProgram;
            var exe = Path.GetFileNameWithoutExtension(path).ToLowerInvariant();
            if (exe == "explorer")
            {
                path = WindowDetector.GetPathForExplorerWindow(window.Handle);
                if (string.IsNullOrWhiteSpace(path)) continue;
                type = ProjectActionType.OpenFolder;
            }
            else if (ProjectLauncherService.IsTerminal(path)) type = ProjectActionType.Terminal;
            var layout = LayoutHelper.FromRectangle(new Rectangle((int)frame.X, (int)frame.Y, (int)frame.W, (int)frame.H));
            layout.Maximized = WindowTools.IsMaximized(window.Handle);
            var zone = OmniZonesHostedService.Instance?.CaptureAssignment(window.Handle);
            if (zone is { } assignment) { layout.ZoneLayoutId = assignment.LayoutId; layout.ZoneId = assignment.ZoneId; }
            captured.Add(new CapturedWindow { Type = type, Path = path, WindowTitle = window.Title, Layout = layout, SnapshotZOrder = zOrder++ });
        }
        return captured;
    }

    public List<ProjectAction> CaptureSetupActions() => CaptureCurrentLayout().ConvertAll(w => new ProjectAction
    {
        Type = w.Type, Path = w.Path, WindowTitle = w.WindowTitle, Layout = w.Layout,
        IsCaptured = true, ReuseWindow = w.Type != ProjectActionType.Terminal, SnapshotZOrder = w.SnapshotZOrder
    });
}

public class CapturedWindow
{
    public ProjectActionType Type { get; set; }
    public string Path { get; set; } = "";
    public string WindowTitle { get; set; } = "";
    public int SnapshotZOrder { get; set; }
    public WindowLayout Layout { get; set; } = new();
}
