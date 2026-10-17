using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;
using System.Windows.Input;
using System.Windows.Threading;
using OmniSync.Hub.Infrastructure;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Presentation;

public class OmniSweepItem
{
    public string Title { get; set; } = "";
    public string Subtitle { get; set; } = "";
    public string Shortcut { get; set; } = "";
    public string Category { get; set; } = "";
    public object? Data { get; set; }
}

public class OmniSweepViewModel : INotifyPropertyChanged, IDisposable
{
    private readonly HubSettingsService _settings;
    private readonly ProjectLauncherService _launcher;
    private readonly ProjectSearchService _search;
    private readonly IMacroService _macros;
    private readonly ResourceOpenerService _opener;
    private readonly HubMonitorService _monitor;
    private readonly CalendarService? _calendar;
    private readonly Dispatcher _dispatcher = Dispatcher.CurrentDispatcher;
    private int _generation;
    private bool _disposed;
    private string _searchText = "";
    private OmniSweepItem? _selected;
    public event EventHandler? RequestClose;
    public event Action<Project?, bool>? SnapshotRequested;
    public event Action<Project>? EditSetupRequested;
    public ObservableCollection<OmniSweepItem> Results { get; } = new();
    public ObservableCollection<OmniSweepItem> Scenes { get; } = new();
    public ObservableCollection<CalendarEvent> OngoingEvents { get; } = new();
    public CalendarEvent? NextEvent { get; private set; }
    public string SearchText { get => _searchText; set { if (_searchText == value) return; _searchText = value; OnPropertyChanged(); Refresh(); } }
    public OmniSweepItem? SelectedItem { get => _selected; set { _selected = value; OnPropertyChanged(); } }
    public ICommand ExecuteCommand { get; }
    public ICommand SnapshotCommand { get; }
    public string EmptyMessage => string.IsNullOrWhiteSpace(SearchText) ? "No setups yet. Snapshot your windows or skip capture to create one." : "No matching setups or files.";

    public OmniSweepViewModel(HubSettingsService settingsService, ProjectLauncherService projectLauncherService,
        CalendarService calendarService, ProjectSearchService searchService, IMacroService macroService,
        ResourceOpenerService resourceOpenerService, HubMonitorService monitorService, ClipboardService clipboardService,
        ProcessService processService, AiCliService aiCliService)
    {
        _settings = settingsService; _launcher = projectLauncherService; _search = searchService;
        _macros = macroService; _opener = resourceOpenerService; _monitor = monitorService;
        _calendar = calendarService;
        if (_calendar != null)
        {
            RefreshCalendarState();
            _calendar.Refreshed += OnCalendarRefreshed;
            _ = RefreshCalendarAsync();
        }
        ExecuteCommand = new RelayCommand(async item => await ExecuteItem(item as OmniSweepItem ?? SelectedItem));
        SnapshotCommand = new RelayCommand(_ => SnapshotRequested?.Invoke(null, false));
        _settings.SettingsChanged += OnSettingsChanged;
        Refresh();
    }

    private void OnSettingsChanged(object? sender, EventArgs e) => _dispatcher.BeginInvoke(new Action(() => { if (!_disposed) Refresh(); }));

    private void OnCalendarRefreshed(object? sender, EventArgs e) => _dispatcher.BeginInvoke(new Action(() =>
    {
        if (_disposed) return;
        RefreshCalendarState();
    }));

    private void RefreshCalendarState()
    {
        if (_calendar == null) return;

        var now = DateTime.Now;
        NextEvent = _calendar.GetNextEvent();
        OngoingEvents.Clear();
        foreach (var calendarEvent in _calendar.GetEventsSnapshot()
                     .Where(e => e.End.HasValue &&
                         (e.IsAllDay
                             ? e.Start.Date <= now.Date && e.End.Value.Date > now.Date
                             : e.Start <= now && e.End.Value > now))
                     .OrderBy(e => e.End))
        {
            OngoingEvents.Add(calendarEvent);
        }

        OnPropertyChanged(nameof(NextEvent));
    }

    private async Task RefreshCalendarAsync()
    {
        await _calendar!.RefreshCalendarAsync();
        OnCalendarRefreshed(this, EventArgs.Empty);
    }

    private async void Refresh()
    {
        var generation = ++_generation;
        Scenes.Clear();
        foreach (var macro in _settings.Settings.Macros
                     .Where(m => string.Equals(m.Category?.Trim(), GoogleHomeService.VoiceCategory, StringComparison.OrdinalIgnoreCase)
                                 && !string.IsNullOrWhiteSpace(m.Name))
                     .GroupBy(m => m.Name.Trim(), StringComparer.OrdinalIgnoreCase)
                     .Select(group => group.First()))
            Scenes.Add(new OmniSweepItem { Title = macro.Name.Trim(), Subtitle = "Scene", Category = "Macro", Data = macro });
        var selectedId = (SelectedItem?.Data as Project)?.Id;
        Results.Clear();
        var query = SearchText.Trim();
        foreach (var setup in _settings.Settings.Setups.ToList().Where(s => query.Length == 0 || s.Name.Contains(query, StringComparison.OrdinalIgnoreCase)))
            Results.Add(new OmniSweepItem { Title = setup.Name, Subtitle = setup.Actions.Count + " steps",
                Shortcut = setup.Shortcut, Category = "Setup", Data = setup });
        SelectedItem = Results.FirstOrDefault(r => (r.Data as Project)?.Id == selectedId) ?? Results.FirstOrDefault();
        OnPropertyChanged(nameof(EmptyMessage));
        if (query.Length == 0) return;
        try
        {
            var matches = await _search.SearchAsync(query);
            if (_disposed || generation != _generation) return;
            foreach (var macro in _settings.Settings.Macros.Where(m => m.Name.Contains(query, StringComparison.OrdinalIgnoreCase)))
                Results.Add(new OmniSweepItem { Title = macro.Name, Subtitle = "Macro", Category = "Macro", Data = macro });
            foreach (var match in matches)
                Results.Add(new OmniSweepItem { Title = match.Name, Subtitle = match.Path, Category = "File", Data = match.Path });
            SelectedItem ??= Results.FirstOrDefault();
        }
        catch (Exception ex) { _monitor.AddLogMessage("[OmniSweep] Search failed: " + ex.Message); }
    }

    private async Task ExecuteItem(OmniSweepItem? item)
    {
        if (item == null) return;
        RequestClose?.Invoke(this, EventArgs.Empty);
        await Task.Yield();
        try
        {
            if (item.Data is Project setup) await _launcher.LaunchProject(setup);
            else if (item.Data is MacroConfig macro) await _macros.ExecuteMacroAsync(macro);
            else if (item.Data is string path) { _search.RecordWorkspaceAccess(path); await _opener.OpenResource(path); }
        }
        catch (Exception ex) { _monitor.AddLogMessage("[OmniSweep] Could not open " + item.Title + ": " + ex.Message); }
    }

    public void RequestSnapshot(Project? setup, bool replace = false) => SnapshotRequested?.Invoke(setup, replace);
    public void EditSetup(Project setup) => EditSetupRequested?.Invoke(setup);
    public bool Rename(Project setup, string name)
    {
        if (string.IsNullOrWhiteSpace(name)) return false;
        setup.Name = name.Trim(); _settings.UpdateProject(setup); return true;
    }
    public bool SetShortcut(Project setup, string shortcut)
    {
        shortcut = shortcut.Trim().ToUpperInvariant();
        if (!SetupRules.IsValidShortcut(shortcut) || _settings.Settings.Setups.Any(s => s.Id != setup.Id && s.Shortcut == shortcut)) return false;
        setup.Shortcut = shortcut; _settings.UpdateProject(setup); return true;
    }
    public void Reorder(Project moving, Project target)
    {
        var list = _settings.Settings.Setups;
        var from = list.FindIndex(s => s.Id == moving.Id);
        var to = list.FindIndex(s => s.Id == target.Id);
        if (from < 0 || to < 0 || from == to) return;
        list.RemoveAt(from); list.Insert(to, moving); _settings.SaveSettings();
    }
    public void Dispose()
    {
        _disposed = true; ++_generation; _settings.SettingsChanged -= OnSettingsChanged;
        if (_calendar != null) _calendar.Refreshed -= OnCalendarRefreshed;
    }
    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
