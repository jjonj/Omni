using System;
using System.Collections.Generic;
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
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Presentation
{
    public class OmniSweepItem
    {
        public string Title { get; set; } = "";
        public string Subtitle { get; set; } = "";
        public string Category { get; set; } = "";
        public object? Data { get; set; }
        public ICommand? Command { get; set; }
    }

    public class OmniSweepViewModel : INotifyPropertyChanged
    {
        private readonly HubSettingsService _settingsService;
        private readonly ProjectLauncherService _projectLauncherService;
        private readonly CalendarService _calendarService;
        private readonly ProjectSearchService _searchService;
        private readonly IMacroService _macroService;
        private readonly ResourceOpenerService _resourceOpenerService;
        private readonly HubMonitorService _monitorService;

        public event EventHandler? RequestClose;

        private string _searchText = "";
        public string SearchText
        {
            get => _searchText;
            set
            {
                if (_searchText != value)
                {
                    _searchText = value;
                    OnPropertyChanged();
                    UpdateResults();
                }
            }
        }

        private CalendarEvent? _nextEvent;
        public CalendarEvent? NextEvent
        {
            get => _nextEvent;
            set { _nextEvent = value; OnPropertyChanged(); }
        }

        public ObservableCollection<OmniSweepItem> Results { get; } = new();
        public ObservableCollection<MacroConfig> PinnedMacros { get; } = new();

        private OmniSweepItem? _selectedItem;
        public OmniSweepItem? SelectedItem
        {
            get => _selectedItem;
            set { _selectedItem = value; OnPropertyChanged(); }
        }

        public ICommand ExecuteCommand { get; }
        public ICommand RunMacroCommand { get; }

        public OmniSweepViewModel(
            HubSettingsService settingsService,
            ProjectLauncherService projectLauncherService,
            CalendarService calendarService,
            ProjectSearchService searchService,
            IMacroService macroService,
            ResourceOpenerService resourceOpenerService,
            HubMonitorService monitorService)
        {
            _settingsService = settingsService;
            _projectLauncherService = projectLauncherService;
            _calendarService = calendarService;
            _searchService = searchService;
            _macroService = macroService;
            _resourceOpenerService = resourceOpenerService;
            _monitorService = monitorService;

            ExecuteCommand = new RelayCommand(async p => await ExecuteItem(p as OmniSweepItem ?? SelectedItem));
            RunMacroCommand = new RelayCommand(async p => 
            {
                if (p is MacroConfig m)
                {
                    await _macroService.ExecuteMacroAsync(m);
                    RequestClose?.Invoke(this, EventArgs.Empty);
                }
            });

            InitializeAsync();
        }

        private async void InitializeAsync()
        {
            _monitorService.AddLogMessage("!!! [OmniSweep] InitializeAsync started.");
            await _calendarService.RefreshCalendarAsync();
            NextEvent = _calendarService.GetNextEvent();
            _monitorService.AddLogMessage($"!!! [OmniSweep] Next Event: {NextEvent?.Summary ?? "None"}");
            UpdateResults();
        }

        private async void UpdateResults()
        {
            _monitorService.AddLogMessage("!!! [OmniSweep] UpdateResults triggered.");
            Results.Clear();
            PinnedMacros.Clear();

            // Always show pinned macros in their own section if no search text
            if (string.IsNullOrWhiteSpace(SearchText))
            {
                var pinned = _settingsService.Settings.Macros.Where(m => m.IsPinned).ToList();
                _monitorService.AddLogMessage($"!!! [OmniSweep] Found {pinned.Count} pinned macros.");
                foreach (var m in pinned) PinnedMacros.Add(m);

                // Show Recent Workspaces
                var recents = _searchService.GetRecentWorkspaces();
                _monitorService.AddLogMessage($"!!! [OmniSweep] Found {recents.Count} recent workspaces.");
                foreach (var ws in recents)
                {
                    Results.Add(new OmniSweepItem
                    {
                        Title = System.IO.Path.GetFileName(ws),
                        Subtitle = ws,
                        Category = "Recent Workspace",
                        Data = ws
                    });
                }

                // Show Projects
                _monitorService.AddLogMessage($"!!! [OmniSweep] Found {_settingsService.Settings.Projects.Count} projects.");
                foreach (var project in _settingsService.Settings.Projects)
                {
                    Results.Add(new OmniSweepItem
                    {
                        Title = project.Name,
                        Subtitle = "Environment Launcher",
                        Category = "Project",
                        Data = project
                    });
                }
            }
            else
            {
                // Perform Search
                var searchResults = await _searchService.SearchAsync(SearchText);
                foreach (var res in searchResults)
                {
                    Results.Add(new OmniSweepItem
                    {
                        Title = res.Name,
                        Subtitle = res.Path,
                        Category = res.IsGitRepo ? "Git Repository" : "Directory",
                        Data = res.Path
                    });
                }

                // Filter Macros
                var matchingMacros = _settingsService.Settings.Macros.Where(m => m.Name.Contains(SearchText, StringComparison.OrdinalIgnoreCase));
                foreach (var macro in matchingMacros)
                {
                    Results.Add(new OmniSweepItem
                    {
                        Title = macro.Name,
                        Subtitle = "Macro",
                        Category = "Macro",
                        Data = macro
                    });
                }
            }

            if (Results.Count > 0 && SelectedItem == null)
            {
                SelectedItem = Results[0];
            }
        }

        private async Task ExecuteItem(OmniSweepItem? item)
        {
            if (item == null) return;

            if (item.Data is Project project)
            {
                await _projectLauncherService.LaunchProject(project);
            }
            else if (item.Data is MacroConfig macro)
            {
                await _macroService.ExecuteMacroAsync(macro);
            }
            else if (item.Data is string path)
            {
                _searchService.RecordWorkspaceAccess(path);
                // Default action for directory: Open in ResourceOpener (folder)
                await _resourceOpenerService.OpenResource(path);
            }

            RequestClose?.Invoke(this, EventArgs.Empty);
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }
}
