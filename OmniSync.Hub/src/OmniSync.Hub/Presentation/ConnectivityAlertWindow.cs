using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Media;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using OmniSync.Hub.Logic.Monitoring;
using Button = System.Windows.Controls.Button;
using StackPanel = System.Windows.Controls.StackPanel;
using TextBlock = System.Windows.Controls.TextBlock;
using Brushes = System.Windows.Media.Brushes;

namespace OmniSync.Hub.Presentation;

internal sealed class ConnectivityAlertWindow : Window
{
    private readonly TextBlock _heading = new() { FontSize = 23, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _detail = new() { FontSize = 16, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 16, 0, 18) };
    private readonly TextBlock _footer = new() { FontSize = 12, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 16, 0, 0) };
    private readonly Button _dismiss = new() { Content = "Dismiss", Padding = new Thickness(10, 7, 10, 7) };
    private readonly DispatcherTimer _recoveryTimer = new() { Interval = TimeSpan.FromSeconds(8) };
    private bool _active;
    private bool _dismissed;

    public ConnectivityAlertWindow()
    {
        Title = "OmniHub — connection alert";
        Width = 440;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        Topmost = true;
        ShowActivated = false;
        Foreground = Brushes.White;
        var panel = new StackPanel { Margin = new Thickness(22) };
        panel.Children.Add(new TextBlock { Text = "OMNIHUB • CONNECTION ALERT", FontSize = 12, Margin = new Thickness(0, 0, 0, 12) });
        panel.Children.Add(_heading);
        panel.Children.Add(_detail);
        var buttons = new StackPanel { Orientation = System.Windows.Controls.Orientation.Horizontal };
        _dismiss.Click += (_, _) => Dismiss();
        buttons.Children.Add(_dismiss);
        var open = new Button { Content = "Open Tailscale", Padding = new Thickness(10, 7, 10, 7), Margin = new Thickness(10, 0, 0, 0) };
        open.Click += (_, _) => OpenTailscale();
        buttons.Children.Add(open);
        panel.Children.Add(buttons);
        panel.Children.Add(_footer);
        Content = panel;
        Loaded += (_, _) => PositionOnSecondaryMonitor();
        Closing += OnClosing;
        Closed += (_, _) => _recoveryTimer.Stop();
        _recoveryTimer.Tick += (_, _) => { _recoveryTimer.Stop(); Close(); };
    }

    public void UpdateAlert(ConnectivityAlert alert)
    {
        var wasActive = _active;
        _active = alert.Active;
        _heading.Text = alert.Title;
        _detail.Text = alert.Detail;
        Background = new SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(_active ? "#7D151D" : "#165C38"));
        _footer.Text = _active ? "Monitoring and recovery continue in the background. Dismiss silences this outage until recovery."
            : "The connection is healthy again. This message closes automatically.";
        _dismiss.Visibility = _active ? Visibility.Visible : Visibility.Collapsed;
        _recoveryTimer.Stop();
        if (!_active)
        {
            if (_dismissed) { Close(); return; }
            Present(playSound: false);
            _recoveryTimer.Start();
        }
        else if (!_dismissed && !wasActive) Present(playSound: true);
    }

    private void Present(bool playSound)
    {
        Show();
        Topmost = true;
        UpdateLayout();
        PositionOnSecondaryMonitor();
        if (playSound) SystemSounds.Hand.Play();
    }

    private void Dismiss()
    {
        _dismissed = true;
        Hide();
    }

    private void OnClosing(object? sender, CancelEventArgs args)
    {
        if (!_active || System.Windows.Application.Current?.Dispatcher.HasShutdownStarted == true) return;
        args.Cancel = true;
        Dismiss();
    }

    private void PositionOnSecondaryMonitor()
    {
        var screen = System.Windows.Forms.Screen.AllScreens.FirstOrDefault(item => !item.Primary) ?? System.Windows.Forms.Screen.PrimaryScreen;
        if (screen == null) return;
        var hwnd = new WindowInteropHelper(this).Handle;
        if (hwnd == IntPtr.Zero || !GetWindowRect(hwnd, out var rect)) return;
        var area = screen.WorkingArea;
        SetWindowPos(hwnd, new IntPtr(-1), Math.Max(area.Left, area.Right - (rect.Right - rect.Left) - 24),
            Math.Max(area.Top, area.Bottom - (rect.Bottom - rect.Top) - 24), 0, 0, 0x0001 | 0x0010); // no resize or activation
    }

    private static void OpenTailscale()
    {
        var executable = (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator)
            .Select(directory => Path.Combine(directory.Trim('"'), "tailscale-ipn.exe")).FirstOrDefault(File.Exists);
        if (executable != null) Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true });
    }

    [StructLayout(LayoutKind.Sequential)] private struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
