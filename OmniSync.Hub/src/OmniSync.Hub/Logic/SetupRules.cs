using System;
using System.Collections.Generic;
using System.Linq;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic;

public static class SetupRules
{
    public const string ShortcutKeys = "1234567890QERTYUIOPFGHJKLZXCVBNM";
    public static readonly string[] ShortcutNames = ShortcutKeys.Select(c => c.ToString()).Concat(Enumerable.Range(1, 24).Select(i => "F" + i))
        .Concat(ShortcutKeys.Select(c => "SHIFT+" + c)).Concat(Enumerable.Range(1, 24).Select(i => "SHIFT+F" + i)).ToArray();

    public static void NormalizeShortcuts(IList<Project> setups)
    {
        var used = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var setup in setups)
        {
            setup.Shortcut = (setup.Shortcut ?? "").Trim().ToUpperInvariant();
            if (!IsValidShortcut(setup.Shortcut) || !used.Add(setup.Shortcut))
                setup.Shortcut = "";
        }
        foreach (var setup in setups.Where(s => s.Shortcut.Length == 0))
        {
            var free = ShortcutNames.FirstOrDefault(k => !used.Contains(k));
            if (free == null) break;
            setup.Shortcut = free;
            used.Add(free);
        }
    }

    public static bool IsValidShortcut(string value) => ShortcutNames.Contains(value.ToUpperInvariant());

    // Preserve hand-authored steps and commands when refreshing a snapshot.
    public static void MergeSnapshot(Project setup, IEnumerable<ProjectAction> captured)
    {
        var previous = setup.Actions.Where(a => a.IsCaptured).ToList();
        var added = new List<ProjectAction>();
        foreach (var action in captured)
        {
            var old = previous.FirstOrDefault(a => a.Type == action.Type &&
                string.Equals(a.Path, action.Path, StringComparison.OrdinalIgnoreCase) &&
                string.Equals(a.WindowTitle, action.WindowTitle, StringComparison.OrdinalIgnoreCase));
            old ??= previous.FirstOrDefault(a => a.Type == action.Type && string.Equals(a.Path, action.Path, StringComparison.OrdinalIgnoreCase));
            if (old != null)
            {
                previous.Remove(old);
                action.Id = old.Id;
                action.Command = old.Command;
                action.Arguments = old.Arguments;
                action.WorkingDirectory = old.WorkingDirectory;
                action.ReuseWindow = old.ReuseWindow;
                action.PositionWindow = old.PositionWindow;
            }
            action.IsCaptured = true;
            added.Add(action);
        }
        var manual = setup.Actions.Where(a => !a.IsCaptured).ToList();
        setup.Actions.Clear();
        foreach (var action in added.Concat(manual)) setup.Actions.Add(action);
    }

    public static MonitorTarget ResolveMonitor(WindowLayout layout, IReadOnlyList<MonitorTarget> monitors)
    {
        if (monitors.Count == 0) throw new InvalidOperationException("No monitors available.");
        if (string.IsNullOrEmpty(layout.MonitorDevice) && string.IsNullOrEmpty(layout.MonitorIdentity))
            return monitors.FirstOrDefault(m => m.Primary) ?? monitors[0];
        var exact = monitors.FirstOrDefault(m => !string.IsNullOrEmpty(layout.MonitorIdentity) && m.Identity == layout.MonitorIdentity);
        if (exact != null) return exact;
        if (string.IsNullOrEmpty(layout.MonitorIdentity))
        {
            exact = monitors.FirstOrDefault(m => m.Device == layout.MonitorDevice);
            if (exact != null) return exact;
        }
        // Prefer a similarly sized display, then one with the same primary role and nearby location.
        return monitors.OrderBy(m => Math.Abs(m.Width - layout.MonitorWidth) + Math.Abs(m.Height - layout.MonitorHeight))
            .ThenBy(m => m.Primary == layout.MonitorPrimary ? 0 : 1)
            .ThenBy(m => Math.Abs(m.X - layout.MonitorX) + Math.Abs(m.Y - layout.MonitorY)).First();
    }
}

public sealed record MonitorTarget(string Device, string Identity, bool Primary, double X, double Y, double Width, double Height);
