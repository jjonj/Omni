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
            var defaults = new List<HotkeyConfig>
            {
                new HotkeyConfig { Name = "TFT: Clipboard to Must Include", Key = "Ctrl+Alt+I", Action = "TFT_CLIPBOARD_MUST_INCLUDE_SOLVE_NEXT" },
                new HotkeyConfig { Name = "TFT: Clipboard to Current Team", Key = "Ctrl+Alt+T", Action = "TFT_CLIPBOARD_CURRENT_TEAM" },
                new HotkeyConfig { Name = "TFT: Copy Team Code", Key = "Ctrl+Alt+C", Action = "TFT_COPY_SOLUTION_CODE" },
                new HotkeyConfig { Name = "Open Hub Window", Key = "Ctrl+Alt+H", Action = "OPEN_HUB_WINDOW" }
            };

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
