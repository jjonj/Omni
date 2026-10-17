using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using System.Runtime.InteropServices;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic;
using OmniSync.Hub.Logic.Monitoring;
using Brushes = System.Windows.Media.Brushes;
using Color = System.Windows.Media.Color;
using MessageBox = System.Windows.MessageBox;
using HorizontalAlignment = System.Windows.HorizontalAlignment;

namespace OmniSync.Hub.Presentation;

/// <summary>A click-through preparation overlay; the desktop stays fully usable until capture.</summary>
public sealed class SetupSnapshotSession : IDisposable
{
    private readonly HubSettingsService _settings;
    private readonly LayoutCaptureService _capture;
    private readonly KeyboardHook _keyboard;
    private readonly HubMonitorService _monitor;
    private readonly Dispatcher _dispatcher;
    private readonly Project? _existing;
    private readonly bool _replace;
    private readonly Action<Project?, bool> _completed;
    private readonly List<Window> _overlays = new();
    private bool _done;
    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")] private static extern int GetWindowLong(IntPtr hwnd, int index);
    [DllImport("user32.dll", EntryPoint = "SetWindowLongW")] private static extern int SetWindowLong(IntPtr hwnd, int index, int value);

    public SetupSnapshotSession(HubSettingsService settings, LayoutCaptureService capture, KeyboardHook keyboard,
        HubMonitorService monitor, Dispatcher dispatcher, Project? existing, bool replace, Action<Project?, bool> completed)
    {
        _settings = settings; _capture = capture; _keyboard = keyboard; _monitor = monitor; _dispatcher = dispatcher;
        _existing = existing; _replace = replace; _completed = completed;
        _keyboard.IsSnapshotActive = true;
        _keyboard.KeyActionOccurred += OnKey;
        foreach (var screen in System.Windows.Forms.Screen.AllScreens)
        {
            var text = new TextBlock
            {
                Text = "Arrange your setup windows\n\nCtrl + Alt + Enter — Capture\nCtrl + Alt + Backspace — " +
                    (existing == null ? "Skip and edit empty setup" : "Skip and edit setup") + "\nCtrl + Alt + Esc — Cancel",
                FontSize = 22, Foreground = Brushes.White, TextAlignment = TextAlignment.Center,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                Background = new SolidColorBrush(Color.FromArgb(215, 25, 30, 40)), Padding = new Thickness(24)
            };
            var overlay = new Window
            {
                WindowStyle = WindowStyle.None, AllowsTransparency = true, Background = Brushes.Transparent,
                Topmost = true, ShowInTaskbar = false, ShowActivated = false, Focusable = false,
                Content = new Border { BorderBrush = Brushes.DeepSkyBlue, BorderThickness = new Thickness(4), Child = text }
            };
            overlay.SourceInitialized += (_, _) =>
            {
                var hwnd = new WindowInteropHelper(overlay).Handle;
                SetWindowLong(hwnd, -20, GetWindowLong(hwnd, -20) | 0x20 | 0x08000000 | 0x80);
                // Use physical monitor bounds; WPF's Left/Width are device-independent on mixed-DPI desktops.
                SetWindowPos(hwnd, new IntPtr(-1), screen.Bounds.X, screen.Bounds.Y, screen.Bounds.Width, screen.Bounds.Height, 0x0010);
            };
            _overlays.Add(overlay);
            overlay.Show();
            SetWindowPos(new WindowInteropHelper(overlay).Handle, new IntPtr(-1), screen.Bounds.X, screen.Bounds.Y, screen.Bounds.Width, screen.Bounds.Height, 0x0010);
        }
    }
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int w, int h, uint flags);

    private void OnKey(object? sender, KeyHookEventArgs e)
    {
        if (_done || e.IsInjected || e.State != KeyState.Down || !e.Control || !e.Alt) return;
        var key = e.Key;
        if (key is not (System.Windows.Forms.Keys.Enter or System.Windows.Forms.Keys.Back or System.Windows.Forms.Keys.Escape)) return;
        e.Handled = true;
        // Mark before dispatch to ignore auto-repeat and prevent two captures.
        _done = true;
        _dispatcher.BeginInvoke(new Action(() => Finish(key)));
    }

    private void Finish(System.Windows.Forms.Keys key)
    {
        Dispose();
        if (key == System.Windows.Forms.Keys.Escape) { _completed(null, false); return; }
        try
        {
            var setup = _existing == null ? new Project { Name = "New Setup" } :
                JsonSerializer.Deserialize<Project>(JsonSerializer.Serialize(_existing))!;
            var skip = key == System.Windows.Forms.Keys.Back;
            if (!skip)
            {
                var actions = _capture.CaptureSetupActions();
                if (_replace) { setup.Actions.Clear(); foreach (var action in actions) setup.Actions.Add(action); }
                else SetupRules.MergeSnapshot(setup, actions);
            }
            if (_existing == null) _settings.AddProject(setup); else _settings.UpdateProject(setup);
            _monitor.AddLogMessage($"[Setups] {(skip ? "Created/opened for editing" : "Captured")}: {setup.Name} ({setup.Actions.Count} steps)");
            _completed(setup, skip);
        }
        catch (Exception ex)
        {
            _monitor.AddLogMessage("[Setups] Snapshot failed: " + ex.Message);
            MessageBox.Show(ex.Message, "Snapshot failed");
            _completed(null, false);
        }
    }

    public void Dispose()
    {
        _done = true;
        _keyboard.KeyActionOccurred -= OnKey;
        _keyboard.IsSnapshotActive = false;
        foreach (var overlay in _overlays) overlay.Close();
        _overlays.Clear();
    }
}
