using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text.Json;
using System.Windows.Threading;
using Microsoft.Extensions.Logging.Abstractions;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Presentation;
using OmniZones.Engine;
using OmniZones.Native;

internal static class Program
{
    private static int _checks;
    private static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
        Console.WriteLine("PASS " + message); _checks++;
    }

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--fixture")) return Fixture();
        var temp = Path.Combine(Path.GetTempPath(), "OmniSetupTests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temp);
        try { Run(temp, args.Contains("--desktop")); Console.WriteLine($"{_checks} setup checks passed."); return 0; }
        catch (Exception ex) { Console.Error.WriteLine(ex); return 1; }
        finally { Directory.Delete(temp, true); }
    }

    private static void Run(string temp, bool desktop)
    {
        var retired = JsonSerializer.Deserialize<HubSettings>("{\"Projects\":[{\"Name\":\"Retired project\"}]}")!;
        Check(retired.Setups.Count == 0, "Retired projects are discarded rather than migrated");
        var setup = new Project { Name = "Development" };
        setup.Actions.Add(new ProjectAction { Type = ProjectActionType.Terminal, Path = "powershell.exe", IsCaptured = true,
            Command = "npm run dev", WorkingDirectory = "D:\\code", WindowTitle = "Old title", Layout = new() { Width = 500 } });
        var once = new ProjectAction { Type = ProjectActionType.RunOnce, Command = "prepare" };
        setup.Actions.Add(once);
        SetupRules.MergeSnapshot(setup, new[] { new ProjectAction { Type = ProjectActionType.Terminal, Path = "powershell.exe",
            WindowTitle = "Changed title", Layout = new() { Width = 800 } } });
        Check(setup.Actions[0].Command == "npm run dev" && setup.Actions[0].WorkingDirectory == "D:\\code" && setup.Actions[0].Layout!.Width == 800,
            "Re-snapshot updates geometry while keeping terminal startup command and directory");
        Check(setup.Actions.Contains(once), "Re-snapshot preserves manually added one-time commands");
        Check(!setup.Actions[0].ReuseWindow, "Captured terminals default to opening a new window");
        var setups = new List<Project> { setup, new() { Name = "Second" }, new() { Name = "Third" } };
        SetupRules.NormalizeShortcuts(setups);
        var assigned = setups.ToDictionary(s => s.Id, s => s.Shortcut);
        setups.Reverse(); SetupRules.NormalizeShortcuts(setups);
        Check(setups.All(s => s.Shortcut == assigned[s.Id]), "Reordering preserves assigned shortcuts");
        setups.Add(new() { Shortcut = setups[0].Shortcut }); setups.Add(new() { Shortcut = "W" });
        SetupRules.NormalizeShortcuts(setups);
        Check(setups.Select(s => s.Shortcut).Distinct().Count() == setups.Count && setups.All(s => SetupRules.IsValidShortcut(s.Shortcut)),
            "Duplicate and reserved shortcuts are replaced with unused keys");
        var many = Enumerable.Range(0, 70).Select(i => new Project { Name = "Setup " + i }).ToList();
        SetupRules.NormalizeShortcuts(many);
        Check(many.All(s => !string.IsNullOrEmpty(s.Shortcut)) && many.Select(s => s.Shortcut).Distinct().Count() == many.Count,
            "Larger setup collections automatically receive function/Shift shortcuts");
        var monitors = new[] { new MonitorTarget("DISPLAY1", "physical-A", true, 0, 0, 1920, 1080),
            new MonitorTarget("DISPLAY2", "physical-B", false, 1920, 0, 2560, 1440) };
        Check(SetupRules.ResolveMonitor(new(), monitors).Primary, "Manually added windows default to the primary monitor");
        Check(SetupRules.ResolveMonitor(new() { MonitorDevice = "DISPLAY2", MonitorIdentity = "physical-A" }, monitors).Identity == "physical-A",
            "Physical monitor identity survives changed Windows display numbering");
        Check(SetupRules.ResolveMonitor(new() { MonitorIdentity = "removed", MonitorWidth = 2560, MonitorHeight = 1440 }, monitors).Identity == "physical-B",
            "Missing monitors map to the closest surviving display");
        var settingsPath = Path.Combine(temp, "settings.json");
        File.WriteAllText(settingsPath, "{\"Projects\":[{\"Name\":\"old\"}],\"Hotkeys\":[{\"Name\":\"Project Selector\",\"Action\":\"SHOW_PROJECT_SELECTOR\",\"Key\":\"Ctrl+Alt+9\"}]}");
        var settings = new HubSettingsService(NullLogger<HubSettingsService>.Instance, settingsPath);
        Check(settings.Settings.Setups.Count == 0, "Settings reload discards old projects");
        Check(settings.Settings.Hotkeys.Any(h => h.Name == "OmniSweep" && h.Action == "OPEN_OMNISWEEP" && h.Key == "Ctrl+Alt+9"),
            "Existing Project Selector hotkey is renamed while retaining its key");
        setup.GlobalHotkey = "Ctrl+Alt+8"; settings.AddProject(setup);
        var reloaded = new HubSettingsService(NullLogger<HubSettingsService>.Instance, settingsPath);
        Check(reloaded.Settings.Setups.Single().Actions[0].Command == "npm run dev" &&
            reloaded.Settings.Hotkeys.Any(h => h.Action == "LAUNCH_SETUP_" + setup.Id && h.Key == setup.GlobalHotkey),
            "Setups, commands, geometry and global hotkeys survive settings reload");
        Check(!File.ReadAllText(settingsPath).Contains("\"Projects\""), "Retired Projects property is removed from saved settings");
        settings.RemoveHotkeyByAction("LAUNCH_SETUP_" + setup.Id);
        Check(setup.GlobalHotkey == "" && settings.Settings.Hotkeys.All(h => h.Action != "LAUNCH_SETUP_" + setup.Id),
            "Deleting a setup global hotkey clears its setup assignment");
        var output = Path.Combine(temp, "command-output.txt");
        var step = new ProjectAction { Type = ProjectActionType.RunOnce, Path = "powershell.exe", WorkingDirectory = temp,
            Command = "Set-Content -LiteralPath '" + output.Replace("'", "''") + "' -Value ($env:OMNI_CLIPBOARD + '|' + (Get-Location).Path)" };
        using (var process = Process.Start(ProjectLauncherService.ShellInfo(step, "clipboard value", false))!)
        { process.WaitForExit(); Check(process.ExitCode == 0 && File.ReadAllText(output).Trim() == "clipboard value|" + temp,
            "One-time commands execute in their directory with clipboard input"); }
        var shell = ProjectLauncherService.ShellInfo(new() { Type = ProjectActionType.Terminal, Path = "WindowsTerminal.exe", Command = "echo hello" }, "", true);
        Check(shell.Arguments.Contains("-w new") && shell.Arguments.Contains("-NoExit"), "Terminal launches request a new window and retain the shell");
        if (desktop) { DesktopChecks(settings, temp); SweepChecks(settings); }
    }

    private static void DesktopChecks(HubSettingsService settings, string temp)
    {
        using var fixture = Process.Start(new ProcessStartInfo(Environment.ProcessPath!, "--fixture") { UseShellExecute = false })!;
        try
        {
            var capture = new LayoutCaptureService();
            List<ProjectAction> actions = new();
            for (var attempt = 0; attempt < 50; attempt++)
            {
                Thread.Sleep(100);
                actions = capture.CaptureSetupActions().Where(a => a.WindowTitle.StartsWith("OmniSetupFixture")).ToList();
                if (actions.Count == 2) break;
            }
            Check(actions.Count == 2 && actions.All(a => !a.WindowTitle.EndsWith("Minimized")), "Snapshot captures both normal windows and excludes minimized windows");
            var windows = LayoutCaptureService.EnumerateWindows().Where(w => w.Title.StartsWith("OmniSetupFixture")).ToList();
            foreach (var window in windows) WindowTools.Place(window.Handle, new RectD(70, 70, 350, 220));
            var layout = new Project { Name = "Disposable layout", Actions = new(actions) };
            new ProjectLauncherService(null!, settings).LaunchProject(layout).GetAwaiter().GetResult();
            var restored = LayoutCaptureService.EnumerateWindows().Where(w => w.Title.StartsWith("OmniSetupFixture")).ToList();
            Check(restored.Select(w => w.Handle).ToHashSet().SetEquals(windows.Select(w => w.Handle)), "Activating a setup reuses distinct existing windows without duplicates");
            Check(actions.All(a => { var hwnd = restored.Single(w => w.Title == a.WindowTitle).Handle; var bounds = LayoutHelper.CalculateBounds(a.Layout!);
                return WindowTools.IsAt(hwnd, new RectD(bounds.X, bounds.Y, bounds.Width, bounds.Height)); }), "Window positions and dimensions are restored exactly");
            var missing = actions[0];
            missing.Layout!.MonitorIdentity = "disconnected-monitor";
            missing.Layout.MonitorDevice = "removed";
            new ProjectLauncherService(null!, settings).LaunchProject(layout).GetAwaiter().GetResult();
            var fallback = LayoutCaptureService.EnumerateWindows().Where(w => w.Title.StartsWith("OmniSetupFixture")).ToList();
            Check(fallback.Count == 2 && fallback[1].Title == missing.WindowTitle,
                "Windows from a missing monitor are restored behind windows on surviving monitors");
            var zoneStore = new OmniZones.Model.ZoneSettingsStore(Path.Combine(temp, "zone-settings.json"));
            var zoneManager = new OmniZones.Runtime.ZoneManager(zoneStore);
            var zoneMonitor = WindowTools.Monitors().First(m => m.Primary);
            var zoneLayout = zoneStore.Current.ActiveLayout;
            var zone = zoneLayout.Zones.First(z => z.Kind == OmniZones.Model.ZoneKind.Zone);
            var zoneWindow = fallback[0].Handle;
            Check(zoneManager.RestoreAssignment(zoneWindow, zoneLayout.Id, zone.Id, zoneMonitor) &&
                zoneManager.CaptureAssignment(zoneWindow)?.ZoneId == zone.Id,
                "Setup windows can restore an OmniZones assignment");
            var zoneFrame = WindowTools.Frame(zoneWindow)!.Value;
            // Modify the isolated in-memory store; its change notification exercises the real relayout path.
            zoneLayout.Zones.First(z => z.Id == zone.Id).W += 0.1;
            zoneStore.Update(s => s.Layouts = zoneStore.Current.Layouts);
            Check(WindowTools.Frame(zoneWindow)!.Value.W > zoneFrame.W + 20,
                "Restored zone assignments follow subsequent zone sizing changes");

            using var keyboard = new KeyboardHook(NullLogger<KeyboardHook>.Instance);
            var dispatcher = Dispatcher.CurrentDispatcher;
            Project? created = null; bool skipped = false;
            using var session = new SetupSnapshotSession(settings, capture, keyboard, new HubMonitorService(new TestLifetime(), NullLogger<HubMonitorService>.Instance), dispatcher, null, false,
                (saved, skip) => { created = saved; skipped = skip; });
            Check(keyboard.IsSnapshotActive && !keyboard.IsRecording, "Snapshot preparation leaves ordinary desktop typing available");
            var key = new KeyHookEventArgs(System.Windows.Forms.Keys.Back, KeyState.Down, false, true, true);
            typeof(SetupSnapshotSession).GetMethod("OnKey", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(session, new object?[] { null, key });
            Pump();
            Check(key.Handled && skipped && created != null && created.Actions.Count == 0 && settings.Settings.Setups.Contains(created),
                "Skip capture creates and saves an empty setup and requests its editor");
            Check(!keyboard.IsSnapshotActive, "Snapshot completion removes overlays and releases keyboard mode");
        }
        finally { if (!fixture.HasExited) { fixture.Kill(true); fixture.WaitForExit(); } }
    }

    private static void Pump()
    {
        var frame = new DispatcherFrame();
        Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => frame.Continue = false));
        Dispatcher.PushFrame(frame);
    }

    private sealed class RecordingLauncher(HubSettingsService settings) : ProjectLauncherService(null!, settings)
    {
        public Guid? Launched;
        public override Task LaunchProject(Project setup) { Launched = setup.Id; return Task.CompletedTask; }
    }

    private static void SweepChecks(HubSettingsService settings)
    {
        foreach (var name in new[] { "Wartribes", "Omni development", "Writing", "Research", "Daily review", "Video tools", "Planning", "Reading" })
            settings.AddProject(new Project { Name = name });
        var launcher = new RecordingLauncher(settings);
        var monitor = new HubMonitorService(new TestLifetime(), NullLogger<HubMonitorService>.Instance);
        using var keyboard = new KeyboardHook(NullLogger<KeyboardHook>.Instance);
        var vm = new OmniSweepViewModel(settings, launcher, null!, null!, null!, null!, monitor, null!, null!, null!);
        var window = new OmniSweepWindow(vm, keyboard);
        window.Show(); window.Activate(); Pump(); window.UpdateLayout();
        var grid = (System.Windows.Controls.ListBox)window.FindName("ResultsList");
        var search = (System.Windows.Controls.TextBox)window.FindName("SearchInput");
        Check(keyboard.IsSweepActive && !search.IsVisible && grid.IsKeyboardFocusWithin, "Sweep opens with grid focus and search inactive");
        var focused = (System.Windows.UIElement)System.Windows.Input.Keyboard.FocusedElement;
        var moved = focused.MoveFocus(new System.Windows.Input.TraversalRequest(System.Windows.Input.FocusNavigationDirection.Next));
        Check(moved && !grid.IsKeyboardFocusWithin && launcher.Launched == null && window.IsVisible,
            "Tab traversal moves from the grid to controls without activating a setup");
        ((System.Windows.UIElement)System.Windows.Input.Keyboard.FocusedElement).MoveFocus(
            new System.Windows.Input.TraversalRequest(System.Windows.Input.FocusNavigationDirection.Previous));
        Check(grid.IsKeyboardFocusWithin, "Shift+Tab returns focus to the selected grid tile");
        void Key(System.Windows.Input.Key key)
        {
            var e = new System.Windows.Input.KeyEventArgs(System.Windows.Input.Keyboard.PrimaryDevice,
                System.Windows.PresentationSource.FromVisual(window)!, Environment.TickCount, key)
                { RoutedEvent = System.Windows.Input.Keyboard.PreviewKeyDownEvent };
            typeof(OmniSweepWindow).GetMethod("Window_PreviewKeyDown", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(window, new object[] { window, e });
            Pump();
        }
        grid.SelectedIndex = 0;
        Key(System.Windows.Input.Key.S);
        var secondRow = grid.SelectedIndex;
        Key(System.Windows.Input.Key.D);
        Check(secondRow > 1 && grid.SelectedIndex == secondRow + 1, "WASD navigates the grid by rows and columns");
        Key(System.Windows.Input.Key.W); Key(System.Windows.Input.Key.A);
        Check(grid.SelectedIndex == 0, "W and A return selection to the first tile");
        Key(System.Windows.Input.Key.OemQuestion);
        Check(search.IsVisible && search.IsKeyboardFocusWithin, "Search shortcut explicitly focuses the search editor");
        Key(System.Windows.Input.Key.D1);
        Check(launcher.Launched == null && window.IsVisible, "Setup shortcuts are ignored while editing search");
        Key(System.Windows.Input.Key.Escape);
        Check(!search.IsVisible && grid.IsKeyboardFocusWithin, "Escape returns from search to grid navigation");
        var bitmap = new System.Windows.Media.Imaging.RenderTargetBitmap((int)window.ActualWidth, (int)window.ActualHeight, 96, 96, System.Windows.Media.PixelFormats.Pbgra32);
        bitmap.Render(window);
        var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder(); encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(bitmap));
        var artifact = Path.GetFullPath("artifacts/setups-grid-preview.png"); Directory.CreateDirectory(Path.GetDirectoryName(artifact)!);
        using (var stream = File.Create(artifact)) encoder.Save(stream);
        var expected = (vm.SelectedItem!.Data as Project)!.Id;
        Key(System.Windows.Input.Key.Space);
        for (var attempt = 0; launcher.Launched == null && attempt < 20; attempt++) { Thread.Sleep(10); Pump(); }
        Check(launcher.Launched == expected && !window.IsVisible && !keyboard.IsSweepActive, "Space activates the selected setup and releases Sweep keyboard mode");
    }

    private sealed class FixtureForm : System.Windows.Forms.Form
    { protected override bool ShowWithoutActivation => true; }
    private sealed class TestLifetime : Microsoft.Extensions.Hosting.IHostApplicationLifetime
    {
        public CancellationToken ApplicationStarted => CancellationToken.None;
        public CancellationToken ApplicationStopping => CancellationToken.None;
        public CancellationToken ApplicationStopped => CancellationToken.None;
        public void StopApplication() { }
    }
    private static int Fixture()
    {
        System.Windows.Forms.Application.EnableVisualStyles();
        var area = System.Windows.Forms.Screen.PrimaryScreen!.WorkingArea;
        var a = new FixtureForm { Text = "OmniSetupFixture A", StartPosition = System.Windows.Forms.FormStartPosition.Manual,
            Bounds = new System.Drawing.Rectangle(area.X + 90, area.Y + 90, 420, 300) };
        var b = new FixtureForm { Text = "OmniSetupFixture B", StartPosition = System.Windows.Forms.FormStartPosition.Manual,
            Bounds = new System.Drawing.Rectangle(area.X + 550, area.Y + 160, 480, 340) };
        var minimized = new FixtureForm { Text = "OmniSetupFixture Minimized", WindowState = System.Windows.Forms.FormWindowState.Minimized };
        a.Shown += (_, _) => { b.Show(); minimized.Show(); minimized.WindowState = System.Windows.Forms.FormWindowState.Minimized; };
        System.Windows.Forms.Application.Run(a);
        b.Dispose(); minimized.Dispose(); return 0;
    }
}
