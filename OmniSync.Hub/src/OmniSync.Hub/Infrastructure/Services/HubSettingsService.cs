using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class HubSettings
    {
        public Dictionary<string, string> ExeMappings { get; set; } = new();
    }

    public class HubSettingsService
    {
        private readonly ILogger<HubSettingsService> _logger;
        private readonly string _settingsPath;
        private HubSettings _settings = new();

        public HubSettings Settings => _settings;

        public HubSettingsService(ILogger<HubSettingsService> logger)
        {
            _logger = logger;
            _settingsPath = Path.Combine(AppContext.BaseDirectory, "settings.json");
            LoadSettings();
        }

        public void LoadSettings()
        {
            try
            {
                if (File.Exists(_settingsPath))
                {
                    string json = File.ReadAllText(_settingsPath);
                    _settings = JsonSerializer.Deserialize<HubSettings>(json) ?? new HubSettings();
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
    }
}
