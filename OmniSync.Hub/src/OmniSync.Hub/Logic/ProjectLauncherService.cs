using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Logic.Services;
using OmniZones.Engine;
using OmniZones.Native;

namespace OmniSync.Hub.Logic;

public class ProjectLauncherService
{
    private readonly HubSettingsService _settings;
    private readonly IServiceProvider? _services;
    private readonly HashSet<Guid> _launching = new();
    public ProjectLauncherService(ProcessService processService, HubSettingsService settingsService, IServiceProvider? services = null)
    { _settings = settingsService; _services = services; }

    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int w, int h, uint flags);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hwnd, int command);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    private T? Service<T>() where T : class => _services?.GetService(typeof(T)) as T;
    private void Log(string text) => Service<HubMonitorService>()?.AddLogMessage("[Setups] " + text);
    public static bool IsTerminal(string path) => Path.GetFileNameWithoutExtension(path).ToLowerInvariant() is
        "cmd" or "powershell" or "pwsh" or "windowsterminal" or "wt" or "conhost" or "openconsole";

    public virtual async Task LaunchProject(Project setup)
    {
        lock (_launching) { if (!_launching.Add(setup.Id)) return; }
        var used = new HashSet<IntPtr>();
        var foreground = new List<(IntPtr Handle, int? SnapshotZOrder, int ActionIndex)>();
        var clipboard = Service<ClipboardService>()?.GetClipboardText() ?? "";
        Log("Opening " + setup.Name);
        try
        {
            foreach (var (action, order) in setup.Actions.ToList().Select((action, index) => (action, index)))
            {
                try
                {
                    if (action.Type == ProjectActionType.Macro)
                    {
                        var macro = _settings.Settings.Macros.FirstOrDefault(m => m.Id.ToString() == action.Path || m.Name == action.Path);
                        if (macro != null && Service<IMacroService>() is { } macros) await macros.ExecuteMacroAsync(macro);
                        else if (Service<IMacroService>() is { } scripts) await scripts.ExecuteMacroAsync(action.Command);
                        continue;
                    }
                    if (action.Type == ProjectActionType.OmniAction)
                    {
                        // Actions can use clipboard through OMNI_CLIPBOARD in scripts or {clipboard} in JSON payloads.
                        var payloadText = string.IsNullOrWhiteSpace(action.Arguments) ? "{}" : action.Arguments;
                        payloadText = payloadText.Replace("\"{clipboard}\"", JsonSerializer.Serialize(clipboard));
                        using var payload = JsonDocument.Parse(payloadText);
                        if (action.Path.StartsWith("LAUNCH_SETUP_", StringComparison.OrdinalIgnoreCase))
                            throw new InvalidOperationException("A setup cannot launch another setup recursively.");
                        Service<CommandDispatcher>()?.Dispatch(action.Path, payload.RootElement);
                        continue;
                    }
                    if (action.Type == ProjectActionType.RunOnce)
                    {
                        using var once = Process.Start(ShellInfo(action, clipboard, persistent: false));
                        if (once != null) { await once.WaitForExitAsync(); if (once.ExitCode != 0) Log("Command exited with code " + once.ExitCode); }
                        continue;
                    }
                    var window = await OpenWindow(action, used, clipboard);
                    if (window == IntPtr.Zero) { Log("Could not find a window for " + action.Path); continue; }
                    used.Add(window);
                    var fallback = false;
                    if (action.PositionWindow && action.Layout is { } layout)
                    {
                        var monitor = LayoutHelper.TargetScreen(layout);
                        fallback = !LayoutHelper.IsOriginalMonitor(layout, monitor);
                        ShowWindow(window, 9); // restore minimized/maximized before placement
                        var bounds = LayoutHelper.CalculateBounds(layout, monitor);
                        var placedInZone = !string.IsNullOrEmpty(layout.ZoneId) &&
                            OmniZonesHostedService.Instance?.RestoreAssignment(window, layout.ZoneLayoutId, layout.ZoneId, monitor.DeviceName) == true;
                        if (!placedInZone) WindowTools.Place(window, new RectD(bounds.X, bounds.Y, bounds.Width, bounds.Height));
                        if (layout.Maximized) ShowWindow(window, 3);
                        if (fallback) SetWindowPos(window, new IntPtr(1), 0, 0, 0, 0, 0x0013); // HWND_BOTTOM, no activate/move/size
                    }
                    if (!fallback) foreground.Add((window, action.SnapshotZOrder, order));
                }
                catch (Exception ex) { Log(action.Path + ": " + ex.Message); }
            }
            var orderedForeground = foreground
                .OrderBy(w => w.SnapshotZOrder.HasValue ? 0 : 1)
                .ThenBy(w => w.SnapshotZOrder ?? w.ActionIndex).ToList();
            // Minimize only after all setup windows have opened or been reused.
            // A setup with no resolved windows must never clear the desktop.
            if (used.Count > 0) Log("Minimized " + MinimizeUnrelatedWindows(used) + " unrelated windows");
            foreach (var window in orderedForeground.AsEnumerable().Reverse())
                SetWindowPos(window.Handle, IntPtr.Zero, 0, 0, 0, 0, 0x0013);
            if (orderedForeground.Count > 0) WindowTools.Activate(orderedForeground[0].Handle);
            Log("Finished " + setup.Name);
        }
        finally { lock (_launching) _launching.Remove(setup.Id); }
    }

    private static int MinimizeUnrelatedWindows(IReadOnlySet<IntPtr> setupWindows)
    {
        var count = 0;
        foreach (var window in LayoutCaptureService.EnumerateWindows())
        {
            if (setupWindows.Contains(window.Handle) ||
                string.Equals(window.Title, "Program Manager", StringComparison.OrdinalIgnoreCase)) continue;
            if (ShowWindow(window.Handle, 6)) count++; // SW_MINIMIZE, never close the application
        }
        return count;
    }

    private string ResolvePath(string path)
    {
        if (File.Exists(path)) return path;
        return _settings.GetPath(path) ?? path;
    }

    private async Task<IntPtr> OpenWindow(ProjectAction action, HashSet<IntPtr> used, string clipboard)
    {
        var path = ResolvePath(action.Path);
        if (action.Type == ProjectActionType.OpenFolder)
        {
            var existing = WindowDetector.GetExplorerWindowForPath(path);
            if (action.ReuseWindow && existing != IntPtr.Zero && !used.Contains(existing)) return existing;
        }
        else if (action.ReuseWindow)
        {
            var candidates = LayoutCaptureService.EnumerateWindows(includeMinimized: true)
                .Where(w => !used.Contains(w.Handle) && string.Equals(w.Path, path, StringComparison.OrdinalIgnoreCase)).ToList();
            var match = candidates.FirstOrDefault(w => string.Equals(w.Title, action.WindowTitle, StringComparison.OrdinalIgnoreCase));
            if (match.Handle == IntPtr.Zero && !string.IsNullOrWhiteSpace(action.WindowTitle))
                match = candidates.FirstOrDefault(w => w.Title.Contains(action.WindowTitle, StringComparison.OrdinalIgnoreCase));
            if (match.Handle == IntPtr.Zero) match = candidates.FirstOrDefault();
            if (match.Handle != IntPtr.Zero) return match.Handle;
        }

        var before = LayoutCaptureService.EnumerateWindows(includeMinimized: true).Select(w => w.Handle).ToHashSet();
        var info = action.Type == ProjectActionType.Terminal ? ShellInfo(action, clipboard, persistent: true) :
            new ProcessStartInfo { FileName = action.Type == ProjectActionType.OpenFolder ? "explorer.exe" : path,
                Arguments = action.Type == ProjectActionType.OpenFolder ? Quote(path) : action.Arguments, UseShellExecute = true };
        if (!string.IsNullOrWhiteSpace(action.WorkingDirectory)) info.WorkingDirectory = action.WorkingDirectory;
        using var process = Process.Start(info);
        for (var attempt = 0; attempt < 80; attempt++)
        {
            await Task.Delay(150);
            if (action.Type == ProjectActionType.OpenFolder)
            {
                var folder = WindowDetector.GetExplorerWindowForPath(path);
                if (folder != IntPtr.Zero && !used.Contains(folder) && (!before.Contains(folder) || action.ReuseWindow)) return folder;
            }
            var candidates = LayoutCaptureService.EnumerateWindows(includeMinimized: true).Where(w => !used.Contains(w.Handle)).ToList();
            var fresh = candidates.FirstOrDefault(w => !before.Contains(w.Handle) &&
                (string.Equals(w.Path, path, StringComparison.OrdinalIgnoreCase) ||
                (action.Type == ProjectActionType.Terminal && IsTerminal(w.Path))));
            if (fresh.Handle != IntPtr.Zero) return fresh.Handle;
            if (process != null)
            {
                var own = candidates.FirstOrDefault(w => GetWindowThreadProcessId(w.Handle, out var pid) != 0 && pid == process.Id);
                if (own.Handle != IntPtr.Zero) return own.Handle;
            }
        }
        return IntPtr.Zero;
    }

    public static string Quote(string value)
    {
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (var character in value)
        {
            if (character == '\\') { slashes++; continue; }
            if (character == '"') result.Append('\\', slashes * 2 + 1);
            else result.Append('\\', slashes);
            result.Append(character); slashes = 0;
        }
        result.Append('\\', slashes * 2).Append('"');
        return result.ToString();
    }

    public static ProcessStartInfo ShellInfo(ProjectAction action, string clipboard, bool persistent)
    {
        var shell = Path.GetFileNameWithoutExtension(action.Path).ToLowerInvariant();
        var command = action.Command ?? "";
        var encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(command));
        ProcessStartInfo info;
        if (!persistent && !string.IsNullOrWhiteSpace(action.Path) && !IsTerminal(action.Path))
        {
            var extension = Path.GetExtension(action.Path).ToLowerInvariant();
            info = extension switch
            {
                ".cmd" or ".bat" => new ProcessStartInfo("cmd.exe", "/c " + Quote(action.Path) + " " + action.Arguments),
                ".ps1" => new ProcessStartInfo("powershell.exe", "-NoProfile -File " + Quote(action.Path) + " " + action.Arguments),
                _ => new ProcessStartInfo(action.Path, action.Arguments)
            };
            info.UseShellExecute = false;
        }
        else if (persistent && shell is "windowsterminal" or "wt")
        {
            var directory = string.IsNullOrWhiteSpace(action.WorkingDirectory) ? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) : action.WorkingDirectory;
            info = new ProcessStartInfo("wt.exe", "-w new new-tab " + action.Arguments + " -d " + Quote(directory) + " powershell.exe -NoLogo -NoExit" +
                (command.Length > 0 ? " -EncodedCommand " + encoded : "")) { UseShellExecute = false };
        }
        else if (shell == "cmd")
            info = new ProcessStartInfo(File.Exists(action.Path) ? action.Path : "cmd.exe", action.Arguments + " " + (persistent ? "/k " : "/c ") + command) { UseShellExecute = false };
        else
            info = new ProcessStartInfo(shell is "powershell" or "pwsh" && File.Exists(action.Path) ? action.Path : shell == "pwsh" ? "pwsh.exe" : "powershell.exe",
                "-NoLogo " + (persistent ? "-NoExit " : "-NoProfile ") + action.Arguments + " " + (command.Length > 0 ? "-EncodedCommand " + encoded : "")) { UseShellExecute = false };
        info.CreateNoWindow = !persistent;
        if (!string.IsNullOrWhiteSpace(action.WorkingDirectory)) info.WorkingDirectory = action.WorkingDirectory;
        info.Environment["OMNI_CLIPBOARD"] = clipboard;
        return info;
    }
}
