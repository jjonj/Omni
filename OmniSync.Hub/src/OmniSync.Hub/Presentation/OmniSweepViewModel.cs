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

        private OmniSweepItem? _selectedItem;
        public OmniSweepItem? SelectedItem
        {
            get => _selectedItem;
            set { _selectedItem = value; OnPropertyChanged(); }
        }

        public ICommand ExecuteCommand { get; }

        public OmniSweepViewModel(
            HubSettingsService settingsService,
            ProjectLauncherService projectLauncherService,
            CalendarService calendarService,
            ProjectSearchService searchService,
            IMacroService macroService,
            ResourceOpenerService resourceOpenerService)
        {
            _settingsService = settingsService;
            _projectLauncherService = projectLauncherService;
            _calendarService = calendarService;
            _searchService = searchService;
            _macroService = macroService;
            _resourceOpenerService = resourceOpenerService;

            ExecuteCommand = new RelayCommand(async p => await ExecuteItem(p as OmniSweepItem ?? SelectedItem));

            InitializeAsync();
        }

        private async void InitializeAsync()
        {
            await _calendarService.RefreshCalendarAsync();
            NextEvent = _calendarService.GetNextEvent();
            UpdateResults();
        }

        private async void UpdateResults()
        {
            Results.Clear();

            if (string.IsNullOrWhiteSpace(SearchText))
            {
                // Show Recent Workspaces
                foreach (var ws in _searchService.GetRecentWorkspaces())
                {
                    Results.Add(new OmniSweepItem
                    {
                        Title = System.IO.Path.GetFileName(ws),
                        Subtitle = ws,
                        Category = "Recent Workspace",
                        Data = ws
                    });
                }

                // Show Pinned Macros
                var pinnedMacros = _settingsService.Settings.Macros.Where(m => m.IsPinned);
                foreach (var macro in pinnedMacros)
                {
                    Results.Add(new OmniSweepItem
                    {
                        Title = macro.Name,
                        Subtitle = $"Macro • {macro.Commands.Count} steps",
                        Category = "Pinned Macro",
                        Data = macro
                    });
                }

                // Show Projects
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
