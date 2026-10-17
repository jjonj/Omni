using System;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;
using Microsoft.Win32;
using OmniZones.Runtime;

namespace OmniZones.App;

internal static class Program
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "OmniZones";

    [STAThread]
    private static int Main(string[] args)
    {
        var firstRun = !System.IO.File.Exists(OmniZones.Model.ZoneSettingsStore.DefaultPath);
        var app = new System.Windows.Application { ShutdownMode = System.Windows.ShutdownMode.OnExplicitShutdown };

        var runtime = ZoneRuntime.TryStart();
        if (runtime == null)
        {
            var hub = Process.GetProcessesByName("omnihub-production-v3").Length > 0
                || Process.GetProcessesByName("omnihub").Length > 0
                || Process.GetProcessesByName("omnihub-production-v2").Length > 0;
            var other = Process.GetProcessesByName("OmniZones").Length > 1;
            var who = hub ? "The Omni Hub is running and already provides OmniZones (use its tray menu or Settings tab)."
                : other ? "Another OmniZones is already running. Look for its icon in the system tray (check the hidden-icons arrow)."
                : "OmniZones' single-instance lock is held by a process that is not visible to this user (for example an elevated Hub). Close it and try again.";
            System.Windows.MessageBox.Show(who, "OmniZones is already running");
            return 0;
        }
        var logPath = System.IO.Path.Combine(System.IO.Path.GetDirectoryName(runtime.Store.Path)!, "omnizones.log");
        runtime.Activity += message =>
        {
            try
            {
                if (System.IO.File.Exists(logPath) && new System.IO.FileInfo(logPath).Length > 256 * 1024) System.IO.File.Delete(logPath);
                System.IO.File.AppendAllText(logPath, $"{DateTime.Now:HH:mm:ss} {message}{Environment.NewLine}");
            }
            catch { /* logging must never break snapping */ }
        };
        runtime.GetAutostart = () => Registry.CurrentUser.OpenSubKey(RunKey)?.GetValue(RunValue) != null;
        runtime.SetAutostart = on =>
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (on) key.SetValue(RunValue, $"\"{Environment.ProcessPath}\"");
            else key.DeleteValue(RunValue, false);
        };

        using var tray = new TrayIcon(runtime, () => app.Shutdown());
        if (Array.IndexOf(args, "--editor") >= 0) runtime.OpenEditor();
        if (Array.IndexOf(args, "--settings") >= 0) runtime.OpenSettings();
        if (firstRun)
        {
            tray.Balloon("OmniZones is running", "Drag a window by its title bar to see the zones. Right-click this icon for settings.");
            runtime.OpenSettings();
        }
        app.Run();
        runtime.Dispose();
        return 0;
    }
}

/// <summary>The tray icon and its menu: settings, editor, quick toggles, exit.</summary>
internal sealed class TrayIcon : IDisposable
{
    private readonly NotifyIcon _icon;
    private readonly ZoneRuntime _runtime;
    private readonly ToolStripMenuItem _enabled = new("Enabled") { CheckOnClick = false };
    private readonly ToolStripMenuItem _simple = new("Simple mode") { CheckOnClick = false };

    public TrayIcon(ZoneRuntime runtime, Action exit)
    {
        _runtime = runtime;
        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("Settings…", null, (_, _) => runtime.OpenSettings()) { Font = new Font(SystemFonts.MenuFont!, FontStyle.Bold) });
        menu.Items.Add(new ToolStripMenuItem("Edit zones…", null, (_, _) => runtime.OpenEditor()));
        menu.Items.Add(new ToolStripSeparator());
        _simple.Click += (_, _) => runtime.Toggle(s => s.SimpleMode = !s.SimpleMode);
        _enabled.Click += (_, _) => runtime.Toggle(s => s.Enabled = !s.Enabled);
        menu.Items.Add(_simple);
        menu.Items.Add(_enabled);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Exit", null, (_, _) => exit()));
        menu.Opening += (_, _) =>
        {
            var s = runtime.Store.Current;
            _simple.Checked = s.SimpleMode;
            _enabled.Checked = s.Enabled;
        };

        _icon = new NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "OmniZones",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _icon.DoubleClick += (_, _) => runtime.OpenSettings();
    }

    private static Icon LoadIcon()
    {
        try
        {
            if (Environment.ProcessPath is { } p && Icon.ExtractAssociatedIcon(p) is { } i) return i;
        }
        catch { /* fall through */ }
        return SystemIcons.Application;
    }

    public void Balloon(string title, string text)
    {
        _icon.BalloonTipTitle = title;
        _icon.BalloonTipText = text;
        _icon.ShowBalloonTip(5000);
    }

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }
}
