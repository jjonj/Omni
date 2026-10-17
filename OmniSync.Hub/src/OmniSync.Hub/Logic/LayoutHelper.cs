using System;
using System.Drawing;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic;

public static class LayoutHelper
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DisplayDevice
    {
        public int Size;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string Name;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Description;
        public int Flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Id;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Key;
    }
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool EnumDisplayDevices(string device, int index, ref DisplayDevice display, int flags);

    public static string MonitorIdentity(Screen screen)
    {
        var device = new DisplayDevice { Size = Marshal.SizeOf<DisplayDevice>(), Name = "", Description = "", Id = "", Key = "" };
        return EnumDisplayDevices(screen.DeviceName, 0, ref device, 1) ? device.Id : "";
    }

    public static MonitorTarget Describe(Screen screen) => new(screen.DeviceName, MonitorIdentity(screen), screen.Primary,
        screen.WorkingArea.X, screen.WorkingArea.Y, screen.WorkingArea.Width, screen.WorkingArea.Height);

    public static Screen TargetScreen(WindowLayout layout) => Screen.AllScreens.First(s => s.DeviceName ==
        SetupRules.ResolveMonitor(layout, Screen.AllScreens.Select(Describe).ToArray()).Device);

    public static bool IsOriginalMonitor(WindowLayout layout, Screen screen) =>
        string.IsNullOrEmpty(layout.MonitorIdentity) && string.IsNullOrEmpty(layout.MonitorDevice) ||
        (!string.IsNullOrEmpty(layout.MonitorIdentity) ? MonitorIdentity(screen) == layout.MonitorIdentity : screen.DeviceName == layout.MonitorDevice);

    public static Rectangle CalculateBounds(WindowLayout layout, Screen? targetScreen = null)
    {
        var screen = targetScreen ?? TargetScreen(layout);
        var area = screen.WorkingArea;
        var unchanged = IsOriginalMonitor(layout, screen) && area.X == layout.MonitorX && area.Y == layout.MonitorY &&
            area.Width == layout.MonitorWidth && area.Height == layout.MonitorHeight;
        if (!layout.UseRatio && (unchanged || layout.MonitorWidth <= 0 || layout.MonitorHeight <= 0))
            return new Rectangle((int)layout.X, (int)layout.Y, (int)layout.Width, (int)layout.Height);
        var width = Math.Clamp((int)Math.Round(area.Width * layout.RatioWidth), 100, area.Width);
        var height = Math.Clamp((int)Math.Round(area.Height * layout.RatioHeight), 80, area.Height);
        var x = Math.Clamp(area.X + (int)Math.Round(area.Width * layout.RatioX), area.Left, area.Right - width);
        var y = Math.Clamp(area.Y + (int)Math.Round(area.Height * layout.RatioY), area.Top, area.Bottom - height);
        return new Rectangle(x, y, width, height);
    }

    public static WindowLayout FromRectangle(Rectangle rect, Screen? targetScreen = null)
    {
        var screen = targetScreen ?? Screen.FromRectangle(rect);
        var area = screen.WorkingArea;
        return new WindowLayout
        {
            UseRatio = false, MonitorDevice = screen.DeviceName, MonitorIdentity = MonitorIdentity(screen), MonitorPrimary = screen.Primary,
            MonitorX = area.X, MonitorY = area.Y, MonitorWidth = area.Width, MonitorHeight = area.Height,
            X = rect.X, Y = rect.Y, Width = rect.Width, Height = rect.Height,
            RatioX = (double)(rect.X - area.X) / area.Width, RatioY = (double)(rect.Y - area.Y) / area.Height,
            RatioWidth = (double)rect.Width / area.Width, RatioHeight = (double)rect.Height / area.Height
        };
    }
}
