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

    public class HubSettings
    {
        public Dictionary<string, string> ExeMappings { get; set; } = new();
        public List<HotkeyConfig> Hotkeys { get; set; } = new();
        public Dictionary<string, string> AiSessionNames { get; set; } = new();
        public List<string> AutoApprovePatterns { get; set; } = new();
        public List<string> AiPresets { get; set; } = new();
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
            if (_settings.Hotkeys.Count == 0)
            {
                _settings.Hotkeys.Add(new HotkeyConfig { Name = "Send clipboard to TFT must include and solve for level+1", Key = "", Action = "TFT_CLIPBOARD_MUST_INCLUDE_SOLVE_NEXT" });
                _settings.Hotkeys.Add(new HotkeyConfig { Name = "Send Clipboard to TFT current team", Key = "", Action = "TFT_CLIPBOARD_CURRENT_TEAM" });
                _settings.Hotkeys.Add(new HotkeyConfig { Name = "Copy solution [1,2,3,4,5] team code to clipboard", Key = "", Action = "TFT_COPY_SOLUTION_CODE" });
                _settings.Hotkeys.Add(new HotkeyConfig { Name = "Open Hub Window", Key = "Ctrl+Alt+H", Action = "OPEN_HUB_WINDOW" });
                SaveSettings();
            }
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

        public string? GetPath(string key)
        {
            if (_settings.ExeMappings.TryGetValue(key, out var path))
            {
                return path;
            }
            return null;
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

        protected virtual void OnSettingsChanged()
        {
            SettingsChanged?.Invoke(this, EventArgs.Empty);
        }
    }
}
