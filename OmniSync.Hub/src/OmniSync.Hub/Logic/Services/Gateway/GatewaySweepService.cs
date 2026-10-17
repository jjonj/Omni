using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Logic.Services.Gateway;

// One live HubSettingsService is authoritative for desktop OmniSweep, Hub Settings,
// and the Gateway browser. Never cache or independently serialize setup collections.
public sealed class GatewaySweepService
{
    private readonly HubSettingsService _settings;
    private readonly ProjectLauncherService _launcher;
    private readonly IMacroService _macros;
    private readonly ResourceOpenerService _opener;
    private readonly ProjectSearchService _search;
    private readonly CalendarService _calendar;
    private readonly CommandDispatcher _dispatcher;
    private readonly ILogger<GatewaySweepService> _logger;
    private long _revision = 1;

    public GatewaySweepService(
        HubSettingsService settings, ProjectLauncherService launcher, IMacroService macros,
        ResourceOpenerService opener, ProjectSearchService search, CalendarService calendar,
        CommandDispatcher dispatcher, ILogger<GatewaySweepService> logger)
    {
        _settings = settings;
        _launcher = launcher;
        _macros = macros;
        _opener = opener;
        _search = search;
        _calendar = calendar;
        _dispatcher = dispatcher;
        _logger = logger;
        _settings.SettingsChanged += (_, _) => Interlocked.Increment(ref _revision);
    }

    public long Revision => Interlocked.Read(ref _revision);

    private static object DescribeAction(ProjectAction a) => new
    {
        id = a.Id,
        type = (int)a.Type,
        path = a.Path,
        arguments = a.Arguments,
        workingDirectory = a.WorkingDirectory,
        command = a.Command,
        reuseWindow = a.ReuseWindow,
        positionWindow = a.PositionWindow,
        isCaptured = a.IsCaptured
    };

    public Task<object> SnapshotAsync() => OnUi<object>(() =>
    {
        var config = _settings.Settings;
        var now = DateTime.Now;
        var next = _calendar.GetNextEvent();
        var ongoing = _calendar.GetEventsSnapshot()
            .Where(e => e.End.HasValue && (e.IsAllDay
                ? e.Start.Date <= now.Date && e.End.Value.Date > now.Date
                : e.Start <= now && e.End.Value > now))
            .OrderBy(e => e.End).Take(6)
            .Select(e => new { e.Summary, e.Start, e.End, e.IsAllDay }).ToArray();

        return new
        {
            revision = Revision,
            // Only expose data the Sweep actually uses. The full Hub settings
            // file contains private values unrelated to the launch workspace.
            setups = config.Setups.Select(s => new
            {
                id = s.Id, name = s.Name, shortcut = s.Shortcut,
                globalHotkey = s.GlobalHotkey,
                actions = s.Actions.Select(DescribeAction).ToArray()
            }).ToArray(),
            macros = config.Macros.Where(m => !string.IsNullOrWhiteSpace(m.Name))
                .Select(m => new
                {
                    id = m.Id, name = m.Name, category = m.Category,
                    isScene = string.Equals(m.Category?.Trim(), GoogleHomeService.VoiceCategory,
                        StringComparison.OrdinalIgnoreCase)
                }).ToArray(),
            calendar = new
            {
                next = next == null ? null : new { next.Summary, next.Start, next.End, next.IsAllDay },
                ongoing
            }
        };
    });

    public async Task<object> SearchAsync(string query)
    {
        query = query.Trim();
        if (query.Length < 2 || query.Length > 100) return Array.Empty<object>();
        var matches = await _search.SearchAsync(query);
        return matches.Take(40).Select(m => new
        {
            name = m.Name, path = m.Path, isFile = m.IsFile
        }).ToArray();
    }

    // All config mutations run on the WPF dispatcher, just like desktop Sweep
    // edits, to avoid modifying its observable collections on a request thread.
    private static async Task<T> OnUi<T>(Func<T> action)
    {
        var dispatcher = System.Windows.Application.Current?.Dispatcher;
        if (dispatcher == null || dispatcher.CheckAccess()) return action();
        return await dispatcher.InvokeAsync(action);
    }

    public Task<SweepMutationResult> UpdateAsync(Guid id, SweepEdit request) => OnUi(() =>
    {
        if (Revision != request.Revision) return SweepMutationResult.Conflict();
        var setup = _settings.Settings.Setups.FirstOrDefault(s => s.Id == id);
        if (setup == null) return SweepMutationResult.Missing();
        var name = (request.Name ?? "").Trim();
        if (name.Length is < 1 or > 120) return SweepMutationResult.Invalid("Name must contain 1–120 characters.");
        var shortcut = (request.Shortcut ?? "").Trim().ToUpperInvariant();
        if (shortcut.Length > 0 &&
            (!SetupRules.IsValidShortcut(shortcut) ||
             _settings.Settings.Setups.Any(s => s.Id != id &&
                 s.Shortcut.Equals(shortcut, StringComparison.OrdinalIgnoreCase))))
            return SweepMutationResult.Invalid("Shortcut is invalid or already assigned.");
        if (request.Actions is { Count: > 100 })
            return SweepMutationResult.Invalid("Too many setup steps.");

        System.Collections.ObjectModel.ObservableCollection<ProjectAction>? steps = null;
        if (request.Actions != null)
        {
            steps = new();
            var seen = new HashSet<Guid>();
            foreach (var edit in request.Actions)
            {
                if (edit.Type is < 0 or > 5 ||
                    (edit.Path?.Length ?? 0) > 4096 ||
                    (edit.Arguments?.Length ?? 0) > 8192 ||
                    (edit.WorkingDirectory?.Length ?? 0) > 4096 ||
                    (edit.Command?.Length ?? 0) > 8192)
                    return SweepMutationResult.Invalid("Invalid setup action.");
                var existing = setup.Actions.FirstOrDefault(a => a.Id == edit.Id);
                if (edit.Id != Guid.Empty && existing == null)
                    return SweepMutationResult.Invalid("Step no longer exists. Refresh before editing.");
                var action = existing == null ? new ProjectAction() : CloneAction(existing);
                if (!seen.Add(action.Id)) return SweepMutationResult.Invalid("Duplicate step.");
                action.Type = (ProjectActionType)edit.Type;
                action.Path = edit.Path ?? "";
                action.Arguments = edit.Arguments ?? "";
                action.WorkingDirectory = edit.WorkingDirectory ?? "";
                action.Command = edit.Command ?? "";
                action.ReuseWindow = edit.ReuseWindow;
                action.PositionWindow = edit.PositionWindow;
                steps.Add(action);
            }
        }
        setup.Name = name;
        setup.Shortcut = shortcut;
        if (steps != null) setup.Actions = steps;
        _settings.UpdateProject(setup);
        return SweepMutationResult.Success(Revision);
    });

    private static ProjectAction CloneAction(ProjectAction a) => new()
    {
        Id = a.Id, Type = a.Type, Path = a.Path, Arguments = a.Arguments,
        WorkingDirectory = a.WorkingDirectory, Command = a.Command,
        WindowTitle = a.WindowTitle, ReuseWindow = a.ReuseWindow,
        IsCaptured = a.IsCaptured, SnapshotZOrder = a.SnapshotZOrder,
        PositionWindow = a.PositionWindow, Layout = a.Layout
    };

    public Task<SweepMutationResult> CreateAsync(SweepCreate request) => OnUi(() =>
    {
        if (Revision != request.Revision) return SweepMutationResult.Conflict();
        var name = (request.Name ?? "").Trim();
        if (name.Length is < 1 or > 120) return SweepMutationResult.Invalid("Name must contain 1–120 characters.");
        _settings.AddProject(new Project { Name = name });
        return SweepMutationResult.Success(Revision);
    });

    public Task<SweepMutationResult> DeleteAsync(Guid id, SweepRevision request) => OnUi(() =>
    {
        if (Revision != request.Revision) return SweepMutationResult.Conflict();
        if (!_settings.Settings.Setups.Any(s => s.Id == id)) return SweepMutationResult.Missing();
        _settings.RemoveProject(id);
        return SweepMutationResult.Success(Revision);
    });

    public Task<SweepMutationResult> ReorderAsync(SweepReorder request) => OnUi(() =>
    {
        if (Revision != request.Revision) return SweepMutationResult.Conflict();
        var setups = _settings.Settings.Setups;
        if (request.Ids is null || request.Ids.Count != setups.Count ||
            request.Ids.Distinct().Count() != setups.Count ||
            request.Ids.Any(id => setups.All(s => s.Id != id)))
            return SweepMutationResult.Invalid("Setup list changed. Refresh before reordering.");
        var byId = setups.ToDictionary(s => s.Id);
        _settings.Settings.Setups = request.Ids.Select(id => byId[id]).ToList();
        _settings.SaveSettings();
        return SweepMutationResult.Success(Revision);
    });

    private async Task ExecuteLoggedAsync(Func<Task> execute)
    {
        try { await execute(); }
        catch (Exception ex) { _logger.LogError(ex, "OmniSweep action failed."); }
    }

    public Task<bool> LaunchAsync(Guid id) => OnUi(() =>
    {
        var setup = _settings.Settings.Setups.FirstOrDefault(s => s.Id == id);
        if (setup == null) return false;
        _ = ExecuteLoggedAsync(() => _launcher.LaunchProject(setup));
        return true;
    });

    public Task<bool> RunMacroAsync(Guid id) => OnUi(() =>
    {
        var macro = _settings.Settings.Macros.FirstOrDefault(m => m.Id == id);
        if (macro == null) return false;
        _ = ExecuteLoggedAsync(() => _macros.ExecuteMacroAsync(macro));
        return true;
    });

    public Task<bool> RequestSnapshotAsync(Guid? id, bool replace) => OnUi(() =>
    {
        var setup = id.HasValue ? _settings.Settings.Setups.FirstOrDefault(s => s.Id == id.Value) : null;
        if (id.HasValue && setup == null) return false;
        _dispatcher.RequestSetupSnapshot(setup, replace);
        return true;
    });

    public async Task<bool> OpenSearchResultAsync(string query, string path)
    {
        if (string.IsNullOrWhiteSpace(path) || query.Length is < 2 or > 100) return false;
        // A caller may open only a current result of a bounded search, not an
        // arbitrary command or filesystem target supplied to the privileged API.
        var matches = await _search.SearchAsync(query.Trim());
        if (!matches.Any(m => string.Equals(m.Path, path, StringComparison.OrdinalIgnoreCase)))
            return false;
        _search.RecordWorkspaceAccess(path);
        await OnUi(() => { _ = ExecuteLoggedAsync(() => _opener.OpenResource(path)); return true; });
        return true;
    }
}

public sealed record SweepRevision(long Revision);
public sealed record SweepCreate(long Revision, string Name);
public sealed record SweepReorder(long Revision, List<Guid> Ids);
public sealed record SweepOpen(string Query, string Path);
public sealed record SweepSnapshot(Guid? Id, bool Replace);
public sealed record SweepActionEdit(
    Guid Id, int Type, string? Path, string? Arguments, string? WorkingDirectory,
    string? Command, bool ReuseWindow, bool PositionWindow);
public sealed record SweepEdit(long Revision, string Name, string Shortcut, List<SweepActionEdit>? Actions);
public sealed record SweepMutationResult(bool Ok, int Status, string? Error, long? Revision)
{
    public static SweepMutationResult Success(long revision) => new(true, 200, null, revision);
    public static SweepMutationResult Conflict() => new(false, 409, "OmniSweep changed elsewhere. Refresh and retry.", null);
    public static SweepMutationResult Missing() => new(false, 404, "Setup not found.", null);
    public static SweepMutationResult Invalid(string message) => new(false, 400, message, null);
}
