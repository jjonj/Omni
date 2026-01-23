using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class HotkeyConfig
    {
        public string Name { get; set; } = string.Empty;
        public string Key { get; set; } = string.Empty;
        public string Action { get; set; } = string.Empty;
    }

    public enum ProjectActionType
    {
        OpenFolder,
        RunProgram
    }

    public class WindowLayout
    {
        public bool UseRatio { get; set; } = true;
        // Absolute
        public double X { get; set; }
        public double Y { get; set; }
        public double Width { get; set; } = 800;
        public double Height { get; set; } = 600;
        // Ratio (0.0 to 1.0)
        public double RatioX { get; set; } = 0.25;
        public double RatioY { get; set; } = 0.25;
        public double RatioWidth { get; set; } = 0.5;
        public double RatioHeight { get; set; } = 0.5;
    }

    public class ProjectAction
    {
        public ProjectActionType Type { get; set; }
        public string Path { get; set; } = string.Empty;
        public string Arguments { get; set; } = string.Empty;
        public WindowLayout? Layout { get; set; }
    }

    public class Project
    {
        public Guid Id { get; set; } = Guid.NewGuid();
        public string Name { get; set; } = "";
        public string HotkeyName { get; set; } = "";
        public System.Collections.ObjectModel.ObservableCollection<ProjectAction> Actions { get; set; } = new();
    }

    public class HubSettings
    {
        public bool UseOneCommander { get; set; } = false;
        public bool AiDebugMode { get; set; } = true;
        public Dictionary<string, string> ExeMappings { get; set; } = new();
        public List<HotkeyConfig> Hotkeys { get; set; } = new();
        public Dictionary<string, string> AiSessionNames { get; set; } = new();
        public List<string> AutoApprovePatterns { get; set; } = new List<string>();
        public List<string> AiPresets { get; set; } = new List<string>();
        public List<Project> Projects { get; set; } = new List<Project>();
        public List<string> BrowserCleanupPatterns { get; set; } = new List<string>();

        // Tell PC Settings
        public string TellPcWorkspace { get; set; } = @"B:\GDrive\Tools";
        public string TellPcSystemContext { get; set; } = "You are to help the user execute commands and tools on this windows PC. You are an expert assistant with full system access. Be concise and efficient.";
        public bool TellPcSoundEnabled { get; set; } = true;
    }

    public class HubSettingsService
    {
        private readonly ILogger<HubSettingsService> _logger;
        private readonly string _settingsPath;
        private HubSettings _settings = new();

        public HubSettings Settings => _settings;

        public event EventHandler? SettingsChanged;

        public HubSettingsService(ILogger<HubSettingsService> logger)
        {
            _logger = logger;
            
            // Use AppData for persistent storage across reinstalls
            string appDataPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync");
            if (!Directory.Exists(appDataPath))
            {
                Directory.CreateDirectory(appDataPath);
            }
            _settingsPath = Path.Combine(appDataPath, "settings.json");
            
            LoadSettings();
            InitializeDefaultHotkeys();
            InitializeDefaultAutoApprovals();
            InitializeDefaultPresets();
            InitializeDefaultProjects();
            InitializeTellPcSettings();
        }

        private void InitializeTellPcSettings()
        {
            bool changed = false;
            if (string.IsNullOrEmpty(_settings.TellPcWorkspace))
            {
                _settings.TellPcWorkspace = @"B:\GDrive\Tools";
                changed = true;
            }
            if (string.IsNullOrEmpty(_settings.TellPcSystemContext))
            {
                _settings.TellPcSystemContext = "You are to help the user execute commands and tools on this windows PC. You are an expert assistant with full system access. Be concise and efficient.";
                changed = true;
            }
            if (changed) SaveSettings();
        }

        private void InitializeDefaultProjects()
        {
            if (_settings.Projects == null) _settings.Projects = new();
        }

        private void InitializeDefaultPresets()
        {
            if (_settings.AiPresets == null || _settings.AiPresets.Count == 0)
            {
                _settings.AiPresets = new List<string> 
                { 
                    "/model", 
                    "/conductor:newTrack", 
                    "/conductor:implement", 
                    "Please run any final scripts and commit all changes" 
                };
                SaveSettings();
            }
        }

        private void InitializeDefaultAutoApprovals()
        {
            if (_settings.AutoApprovePatterns == null || _settings.AutoApprovePatterns.Count == 0)
            {
                _settings.AutoApprovePatterns = new List<string> { "aispeak.py" };
                SaveSettings();
            }
        }

        private void InitializeDefaultHotkeys()
        {
            var defaults = new List<HotkeyConfig>
            {
                                                new HotkeyConfig { Name = "TFT: Activate Current Team", Key = "Ctrl+1", Action = "TFT_ACTIVATE_CURRENT_TEAM" },
                                                new HotkeyConfig { Name = "TFT: Activate Must Include", Key = "Ctrl+2", Action = "TFT_ACTIVATE_MUST_INCLUDE" },
                                                new HotkeyConfig { Name = "TFT: Enter Active Mode", Key = "Alt+A", Action = "TFT_ENTER_ADD_MODE" },
                                                new HotkeyConfig { Name = "TFT: Run Optimization", Key = "Alt+S", Action = "TFT_RUN_OPTIMIZATION" },                                                                                                                new HotkeyConfig { Name = "TFT: Toggle Smart Sort", Key = "Ctrl+M", Action = "TFT_TOGGLE_SMART_SORT" },
                                                                                                                new HotkeyConfig { Name = "TFT: Clear All", Key = "Alt+X", Action = "TFT_CLEAR_ALL" },
                                                                                                                new HotkeyConfig { Name = "TFT: Save Comp", Key = "Alt+G", Action = "TFT_SAVE_COMP" }, 
                                                                                                                new HotkeyConfig { Name = "TFT: Tab Solver", Key = "Alt+B", Action = "TFT_SWITCH_TAB_SOLVER" },
                                                                                                                new HotkeyConfig { Name = "TFT: Tab Quiz", Key = "Alt+Q", Action = "TFT_SWITCH_TAB_QUIZ" },
                                                                                                                new HotkeyConfig { Name = "TFT: Tab Config", Key = "Alt+K", Action = "TFT_SWITCH_TAB_CONFIG" },
                                                                                                                new HotkeyConfig { Name = "TFT: Tab Director", Key = "Alt+D", Action = "TFT_SWITCH_TAB_DIRECTOR" },
                                                                                                                new HotkeyConfig { Name = "TFT: Copy Active", Key = "Alt+C", Action = "TFT_COPY_ACTIVE" },
                                                                                                                new HotkeyConfig { Name = "TFT: Paste Active", Key = "Alt+V", Action = "TFT_PASTE_ACTIVE" },                                                                                new HotkeyConfig { Name = "TFT: Clear Active", Key = "Shift+X", Action = "TFT_CLEAR_ACTIVE" },
                                                                                new HotkeyConfig { Name = "TFT: Cycle Highlights", Key = "Ctrl+Tab", Action = "TFT_CYCLE_UNIT_HIGHLIGHT" },
                                                                                new HotkeyConfig { Name = "TFT: Next Result", Key = "Tab", Action = "TFT_CYCLE_RESULT_NEXT" },
                                                                new HotkeyConfig { Name = "TFT: Toggle Cost 1", Key = "Alt+1", Action = "TFT_TOGGLE_COST_1" },
                                new HotkeyConfig { Name = "TFT: Toggle Cost 2", Key = "Alt+2", Action = "TFT_TOGGLE_COST_2" },
                                new HotkeyConfig { Name = "TFT: Toggle Cost 3", Key = "Alt+3", Action = "TFT_TOGGLE_COST_3" },
                                new HotkeyConfig { Name = "TFT: Toggle Cost 4", Key = "Alt+4", Action = "TFT_TOGGLE_COST_4" },
                                new HotkeyConfig { Name = "TFT: Toggle Cost 5", Key = "Alt+5", Action = "TFT_TOGGLE_COST_5" },
                                new HotkeyConfig { Name = "TFT: Toggle Level 4", Key = "Shift+4", Action = "TFT_TOGGLE_LEVEL_4" },
                                new HotkeyConfig { Name = "TFT: Toggle Level 5", Key = "Shift+5", Action = "TFT_TOGGLE_LEVEL_5" },
                                new HotkeyConfig { Name = "TFT: Toggle Level 6", Key = "Shift+6", Action = "TFT_TOGGLE_LEVEL_6" },
                                new HotkeyConfig { Name = "TFT: Toggle Level 7", Key = "Shift+7", Action = "TFT_TOGGLE_LEVEL_7" },
                                new HotkeyConfig { Name = "TFT: Toggle Level 8", Key = "Shift+8", Action = "TFT_TOGGLE_LEVEL_8" },
                                new HotkeyConfig { Name = "TFT: Toggle Level 9", Key = "Shift+9", Action = "TFT_TOGGLE_LEVEL_9" },
                                new HotkeyConfig { Name = "TFT: Toggle Level 10", Key = "Shift+0", Action = "TFT_TOGGLE_LEVEL_10" },
                                new HotkeyConfig { Name = "TFT: POC", Key = "Ctrl+Alt+P", Action = "TFT_POC" },
                                new HotkeyConfig { Name = "Chrome: Reload Extension", Key = "Ctrl+Alt+R", Action = "RELOAD_CHROME_EXTENSION" },
                                new HotkeyConfig { Name = "Open Hub Window", Key = "Ctrl+Alt+H", Action = "OPEN_HUB_WINDOW" }            };

            bool changed = false;
            if (_settings.Hotkeys == null) _settings.Hotkeys = new List<HotkeyConfig>();

            foreach (var def in defaults)
            {
                var existing = _settings.Hotkeys.Find(h => h.Action == def.Action);
                if (existing == null)
                {
                    _settings.Hotkeys.Add(def);
                    changed = true;
                }
                else if ((def.Action.StartsWith("TFT_") || def.Action == "RELOAD_CHROME_EXTENSION") && existing.Key != def.Key)
                {
                    // FORCE update TFT and critical hotkeys to new defaults
                    _logger.LogInformation($"Updating critical hotkey {def.Action} from {existing.Key} to {def.Key}");
                    existing.Key = def.Key;
                    changed = true;
                }
                else if (string.IsNullOrEmpty(existing.Key))
                {
                    existing.Key = def.Key;
                    changed = true;
                }
            }

            if (changed) SaveSettings();
        }

        public void LoadSettings()
        {
            try
            {
                if (File.Exists(_settingsPath))
                {
                    string json = File.ReadAllText(_settingsPath);
                    _settings = JsonSerializer.Deserialize<HubSettings>(json) ?? new HubSettings();
                    if (_settings.AiSessionNames == null) _settings.AiSessionNames = new();
                    _logger.LogInformation("Settings loaded successfully.");
                }
                else
                {
                    _settings = new HubSettings();
                    SaveSettings();
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error loading settings.");
                _settings = new HubSettings();
            }
        }

        public void SaveSettings()
        {
            try
            {
                string json = JsonSerializer.Serialize(_settings, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(_settingsPath, json);
                _logger.LogInformation("Settings saved successfully.");
                OnSettingsChanged();
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error saving settings.");
            }
        }

        public void AddMapping(string key, string path)
        {
            _settings.ExeMappings[key] = path;
            SaveSettings();
        }

        public void RemoveMapping(string key)
        {
            if (_settings.ExeMappings.Remove(key))
            {
                SaveSettings();
            }
        }

        public void AddHotkey(HotkeyConfig hotkey)
        {
            _settings.Hotkeys.Add(hotkey);
            SaveSettings();
        }

        public void RemoveHotkey(string name)
        {
            _settings.Hotkeys.RemoveAll(h => h.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
            SaveSettings();
        }

        public void UpdateHotkeys(List<HotkeyConfig> hotkeys)
        {
            _settings.Hotkeys = hotkeys;
            SaveSettings();
        }

        public void UpdateTellPcSettings(string workspace, string systemContext, bool soundEnabled)
        {
            _settings.TellPcWorkspace = workspace;
            _settings.TellPcSystemContext = systemContext;
            _settings.TellPcSoundEnabled = soundEnabled;
            SaveSettings();
        }

        public string? GetPath(string key)
        {
            if (_settings.ExeMappings.TryGetValue(key, out var path))
            {
                return path;
            }
            return null;
        }

        public void SetPath(string key, string path)
        {
            _settings.ExeMappings[key] = path;
            SaveSettings();
        }

        public void SetAiSessionName(string key, string name)
        {
            _settings.AiSessionNames[key] = name;
            SaveSettings();
        }

        public string? GetAiSessionName(string key)
        {
            return _settings.AiSessionNames.TryGetValue(key, out var name) ? name : null;
        }

        public void RemoveAiSessionName(string key)
        {
            if (_settings.AiSessionNames.Remove(key))
            {
                SaveSettings();
            }
        }

        public List<string> GetAiPresets()
        {
            return _settings.AiPresets ?? new List<string>();
        }

        public void AddAiPreset(string preset)
        {
            if (_settings.AiPresets == null) _settings.AiPresets = new List<string>();
            if (!_settings.AiPresets.Contains(preset))
            {
                _settings.AiPresets.Add(preset);
                SaveSettings();
            }
        }

        public void RemoveAiPreset(string preset)
        {
            if (_settings.AiPresets != null && _settings.AiPresets.Remove(preset))
            {
                SaveSettings();
            }
        }

        public void AddProject(Project project)
        {
            if (_settings.Projects == null) _settings.Projects = new();
            _settings.Projects.Add(project);
            SaveSettings();
        }

        public void RemoveProject(Guid id)
        {
            if (_settings.Projects != null && _settings.Projects.RemoveAll(p => p.Id == id) > 0)
            {
                SaveSettings();
            }
        }

        public void UpdateProject(Project project)
        {
            if (_settings.Projects == null) return;
            var index = _settings.Projects.FindIndex(p => p.Id == project.Id);
            if (index != -1)
            {
                _settings.Projects[index] = project;
                SaveSettings();
            }
        }

        protected virtual void OnSettingsChanged()
        {
            SettingsChanged?.Invoke(this, EventArgs.Empty);
        }
    }
}
