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
        private readonly ClipboardService _clipboardService;
        private readonly ProcessService _processService;
        private readonly AiCliService _aiCliService;

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
        public ObservableCollection<MacroConfig> MacroTiles { get; } = new();

        private OmniSweepItem? _selectedItem;
        public OmniSweepItem? SelectedItem
        {
            get => _selectedItem;
            set { _selectedItem = value; OnPropertyChanged(); }
        }

        public ICommand ExecuteCommand { get; }
        public ICommand RunMacroCommand { get; }
        public ICommand CreateTaskFromClipboardCommand { get; }
        public ICommand RunGmiCommand { get; }
        public ICommand RunGmiJobCommand { get; }

        public OmniSweepViewModel(
            HubSettingsService settingsService,
            ProjectLauncherService projectLauncherService,
            CalendarService calendarService,
            ProjectSearchService searchService,
            IMacroService macroService,
            ResourceOpenerService resourceOpenerService,
            HubMonitorService monitorService,
            ClipboardService clipboardService,
            ProcessService processService,
            AiCliService aiCliService)
        {
            _settingsService = settingsService;
            _projectLauncherService = projectLauncherService;
            _calendarService = calendarService;
            _searchService = searchService;
            _macroService = macroService;
            _resourceOpenerService = resourceOpenerService;
            _monitorService = monitorService;
            _clipboardService = clipboardService;
            _processService = processService;
            _aiCliService = aiCliService;

            ExecuteCommand = new RelayCommand(async p => await ExecuteItem(p as OmniSweepItem ?? SelectedItem));
            RunMacroCommand = new RelayCommand(async p => 
            {
                if (p is MacroConfig m)
                {
                    await _macroService.ExecuteMacroAsync(m);
                    RequestClose?.Invoke(this, EventArgs.Empty);
                }
            });
            CreateTaskFromClipboardCommand = new RelayCommand(async _ => await ExecuteCreateTaskFromClipboard());
            RunGmiCommand = new RelayCommand(async _ => await ExecuteRunGmi());
            RunGmiJobCommand = new RelayCommand(async _ => await ExecuteRunGmiJob());

            InitializeAsync();
        }

        private async Task ExecuteRunGmi()
        {
            try
            {
                _monitorService.AddLogMessage("[OmniSweep] Launching Wartribes GMI via AiCliService");
                await _aiCliService.LaunchSessionAsync(@"D:\Wartribes");
                RequestClose?.Invoke(this, EventArgs.Empty);
            }
            catch (Exception ex)
            {
                _monitorService.AddLogMessage($"[OmniSweep] Error launching GMI: {ex.Message}");
            }
        }

        private async Task ExecuteRunGmiJob()
        {
            try
            {
                string jobName = _clipboardService.GetClipboardText();
                if (string.IsNullOrWhiteSpace(jobName))
                {
                    _monitorService.AddLogMessage("[OmniSweep] Clipboard empty, cannot run job");
                    return;
                }
                _monitorService.AddLogMessage($"[OmniSweep] Launching Wartribes Job via AiCliService: {jobName}");
                await _aiCliService.LaunchSessionAsync(@"D:\Wartribes", job: jobName);
                RequestClose?.Invoke(this, EventArgs.Empty);
            }
            catch (Exception ex)
            {
                _monitorService.AddLogMessage($"[OmniSweep] Error launching Job: {ex.Message}");
            }
        }

        private async Task ExecuteCreateTaskFromClipboard()
        {
            try 
            {
                string text = _clipboardService.GetClipboardText();
                if (string.IsNullOrWhiteSpace(text)) return;

                string filename = GenerateFilename(text);
                string directory = @"D:\Wartribes\Tasks";
                if (!System.IO.Directory.Exists(directory))
                {
                    System.IO.Directory.CreateDirectory(directory);
                }

                string fullPath = GetUniquePath(directory, filename);
                await System.IO.File.WriteAllTextAsync(fullPath, text);

                _clipboardService.SetClipboardText(fullPath);
                
                _monitorService.AddLogMessage($"[OmniSweep] Created task file: {fullPath}");
                RequestClose?.Invoke(this, EventArgs.Empty);
            }
            catch (Exception ex)
            {
                _monitorService.AddLogMessage($"[OmniSweep] Error creating task: {ex.Message}");
            }
        }

        private string GenerateFilename(string content)
        {
            var lines = content.Split(new[] { "\r\n", "\r", "\n" }, StringSplitOptions.None);
            string? titleLine = lines.FirstOrDefault(l => l.Trim().StartsWith("# "));
            string baseText = "";
            if (titleLine != null)
            {
                baseText = titleLine.Trim().Substring(2).Trim();
            }
            else
            {
                baseText = content;
            }

            // Take max 5 words
            var words = baseText.Split(new[] { ' ', '\t', '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)
                               .Take(5);
            string filenameBase = string.Join("", words.Select(w => Sanitize(w)));
            
            if (string.IsNullOrWhiteSpace(filenameBase)) filenameBase = "UntitledTask";
            
            return filenameBase + ".md";
        }

        private string Sanitize(string word)
        {
            return new string(word.Where(c => char.IsLetterOrDigit(c)).ToArray());
        }

        private string GetUniquePath(string dir, string filename)
        {
            string fullPath = System.IO.Path.Combine(dir, filename);
            if (!System.IO.File.Exists(fullPath)) return fullPath;

            string baseName = System.IO.Path.GetFileNameWithoutExtension(filename);
            string ext = System.IO.Path.GetExtension(filename);
            int counter = 1;
            while (System.IO.File.Exists(System.IO.Path.Combine(dir, $"{baseName}_{counter}{ext}")))
            {
                counter++;
            }
            return System.IO.Path.Combine(dir, $"{baseName}_{counter}{ext}");
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
            MacroTiles.Clear();

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
                
                foreach (var macro in _settingsService.Settings.Macros.Where(m => !m.IsPinned))
                {
                    MacroTiles.Add(macro);
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
                        Category = res.IsGitRepo ? "Git Repository" : (res.IsFile ? "File" : "Directory"),
                        Data = res.Path
                    });
                }

                // Filter Macros
                var matchingMacros = _settingsService.Settings.Macros.Where(m => MacroMatches(m.Name, SearchText));
                foreach (var macro in matchingMacros)
                {
                    MacroTiles.Add(macro);
                }
            }

            if (Results.Count == 0)
            {
                SelectedItem = null;
            }
            else if (SelectedItem == null || !Results.Contains(SelectedItem))
            {
                SelectedItem = Results[0];
            }
        }

        private static bool MacroMatches(string name, string query)
        {
            if (query.IndexOfAny(new[] { '*', '?' }) >= 0)
            {
                var escaped = System.Text.RegularExpressions.Regex.Escape(query)
                    .Replace("\\*", ".*")
                    .Replace("\\?", ".");
                return System.Text.RegularExpressions.Regex.IsMatch(name, escaped, System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            }

            return name.Contains(query, StringComparison.OrdinalIgnoreCase);
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
