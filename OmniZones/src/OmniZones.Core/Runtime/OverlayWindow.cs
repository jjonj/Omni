using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using OmniZones.Engine;
using OmniZones.Native;

namespace OmniZones.Runtime;

internal sealed record OverlayZone(string Name, string Detail, RectD Rect, bool Hot, string? Action, bool Dashed, bool Peek);

/// <summary>
/// Click-through, never-activating translucent window that draws a layout's zones over one monitor.
/// Activating anything mid-drag would cancel the system move loop, hence WS_EX_NOACTIVATE | WS_EX_TRANSPARENT.
/// Content lives on a pixel-sized Canvas inside a Viewbox, so it is correct whatever DPI the host process runs at.
/// </summary>
internal sealed class OverlayWindow : Window
{
    private static readonly Color Tape = Color.FromRgb(0xF5, 0xA5, 0x24);
    private static readonly Color TapeInk = Color.FromRgb(0xFB, 0xE3, 0xB4);

    private readonly Canvas _canvas = new() { ClipToBounds = true };
    private IntPtr _hwnd;
    private bool _visible;
    private string _signature = "";
    private RectD _area;

    public OverlayWindow()
    {
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        Focusable = false;
        Left = -32000; Top = -32000; Width = 1; Height = 1;
        Content = new Viewbox { Stretch = Stretch.Fill, Child = _canvas };
        SourceInitialized += (_, _) =>
        {
            _hwnd = new WindowInteropHelper(this).Handle;
            var ex = Win32.GetWindowLongPtr(_hwnd, Win32.GWL_EXSTYLE).ToInt64();
            Win32.SetWindowLongPtr(_hwnd, Win32.GWL_EXSTYLE,
                (IntPtr)(ex | Win32.WS_EX_LAYERED | Win32.WS_EX_TRANSPARENT | Win32.WS_EX_TOOLWINDOW | Win32.WS_EX_NOACTIVATE));
            HwndSource.FromHwnd(_hwnd)?.AddHook(OnMessage);
        };
        Show();
        Win32.ShowWindow(_hwnd, Win32.SW_HIDE);
    }

    private const int WM_DPICHANGED = 0x02E0;

    /// <summary>
    /// Moving the overlay onto a monitor with another DPI makes Windows send WM_DPICHANGED and WPF then resizes the window
    /// to the suggested rect, so it no longer matches the monitor (zones look cropped). Keep our exact pixel rect instead.
    /// </summary>
    private IntPtr OnMessage(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_DPICHANGED)
        {
            handled = true;
            Fit();
        }
        return IntPtr.Zero;
    }

    private void Fit() =>
        Win32.SetWindowPos(_hwnd, Win32.HWND_TOPMOST, (int)_area.X, (int)_area.Y, (int)_area.W, (int)_area.H, Win32.SWP_NOACTIVATE);

    public void Present(RectD area, IReadOnlyList<OverlayZone> zones)
    {
        var sig = $"{area}|" + string.Join(";", zones.Select(z => $"{z.Name}{z.Detail}{z.Rect}{z.Hot}{z.Action}"));
        if (!_visible || area != _area)
        {
            _area = area;
            Win32.SetWindowPos(_hwnd, Win32.HWND_TOPMOST, (int)area.X, (int)area.Y, (int)area.W, (int)area.H,
                Win32.SWP_NOACTIVATE | Win32.SWP_SHOWWINDOW);
            _visible = true;
            _signature = "";
            // Backstop in case a DPI change slipped through: the window must cover exactly this monitor.
            if (Win32.GetWindowRect(_hwnd, out var wr) && (wr.Left != (int)area.X || wr.Top != (int)area.Y || wr.Right - wr.Left != (int)area.W || wr.Bottom - wr.Top != (int)area.H)) Fit();
        }
        if (sig == _signature) return;
        _signature = sig;
        Render(area, zones);
    }

    public void HideOverlay()
    {
        if (!_visible) return;
        Win32.ShowWindow(_hwnd, Win32.SW_HIDE);
        _visible = false;
        _signature = "";
    }

    private void Render(RectD area, IReadOnlyList<OverlayZone> zones)
    {
        _canvas.Width = area.W; _canvas.Height = area.H;
        _canvas.Children.Clear();
        var scale = Math.Clamp(area.H / 1392.0, 0.7, 1.6);
        foreach (var z in zones.OrderBy(z => z.Peek ? 1 : 0))
        {
            var fill = Color.FromArgb((byte)(z.Hot ? 0x22 : 0x0A), Tape.R, Tape.G, Tape.B);
            var stroke = Color.FromArgb((byte)(z.Hot ? 0xBB : 0x55), Tape.R, Tape.G, Tape.B);
            var panel = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            panel.Children.Add(Text(z.Name, 30 * scale, FontWeights.Bold, TapeInk, "Segoe UI"));
            panel.Children.Add(Text(z.Detail, 17 * scale, FontWeights.Normal, Color.FromArgb(0xD0, TapeInk.R, TapeInk.G, TapeInk.B), "Consolas"));
            if (z.Action != null && z.Hot)
            {
                panel.Children.Add(new Border
                {
                    Background = new SolidColorBrush(Tape), CornerRadius = new CornerRadius(6 * scale),
                    Padding = new Thickness(14 * scale, 5 * scale, 14 * scale, 5 * scale), Margin = new Thickness(0, 12 * scale, 0, 0),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Child = Text(z.Action, 22 * scale, FontWeights.SemiBold, Color.FromRgb(0x1B, 0x13, 0x05), "Segoe UI"),
                });
            }
            var box = new Border
            {
                Width = Math.Max(1, z.Rect.W), Height = Math.Max(1, z.Rect.H), CornerRadius = new CornerRadius(12 * scale),
                Background = new SolidColorBrush(fill), BorderBrush = new SolidColorBrush(stroke),
                BorderThickness = new Thickness(3 * scale), Child = panel, ClipToBounds = true,
            };
            if (z.Dashed) box.BorderBrush = new SolidColorBrush(stroke);
            Canvas.SetLeft(box, z.Rect.X - area.X); Canvas.SetTop(box, z.Rect.Y - area.Y);
            _canvas.Children.Add(box);
        }
    }

    private static TextBlock Text(string text, double size, FontWeight weight, Color color, string font) => new()
    {
        Text = text, FontSize = size, FontWeight = weight, Foreground = new SolidColorBrush(color), FontFamily = new FontFamily(font),
        TextAlignment = TextAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center, TextWrapping = TextWrapping.Wrap,
    };
}
