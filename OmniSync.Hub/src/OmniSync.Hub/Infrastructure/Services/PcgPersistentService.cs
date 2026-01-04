using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using OmniSync.Hub.Models;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class PcgPersistentService
    {
        private readonly string _stateFilePath;
        private Dictionary<string, PcgObjectState> _persistedStates = new();
        private readonly object _lock = new();

        public PcgPersistentService()
        {
            _stateFilePath = Path.Combine(AppContext.BaseDirectory, "pcg_state.json");
            LoadState();
        }

        public void SaveObjectState(string worldId, float x, float y, string data, bool isExclusion = false)
        {
            string hash = GeneratePositionHash(x, y);
            string key = $"{worldId}_{hash}";

            lock (_lock)
            {
                _persistedStates[key] = new PcgObjectState
                {
                    WorldId = worldId,
                    PositionHash = hash,
                    X = x,
                    Y = y,
                    Data = data,
                    IsExclusion = isExclusion,
                    LastModified = DateTime.UtcNow
                };
                PersistToDisk();
            }
        }

        public PcgObjectState? GetObjectState(string worldId, float x, float y)
        {
            string hash = GeneratePositionHash(x, y);
            string key = $"{worldId}_{hash}";

            lock (_lock)
            {
                return _persistedStates.TryGetValue(key, out var state) ? state : null;
            }
        }

        public List<PcgObjectState> GetAllStatesForWorld(string worldId)
        {
            lock (_lock)
            {
                return _persistedStates.Values.Where(s => s.WorldId == worldId).ToList();
            }
        }

        public void RemoveState(string worldId, float x, float y)
        {
            string hash = GeneratePositionHash(x, y);
            string key = $"{worldId}_{hash}";

            lock (_lock)
            {
                if (_persistedStates.Remove(key))
                {
                    PersistToDisk();
                }
            }
        }

        private string GeneratePositionHash(float x, float y)
        {
            // Use a grid-based approach for the hash to allow some tolerance (e.g., 10cm grid)
            int ix = (int)Math.Round(x * 10); 
            int iy = (int)Math.Round(y * 10);
            return $"{ix}_{iy}";
        }

        private void LoadState()
        {
            try
            {
                if (File.Exists(_stateFilePath))
                {
                    string json = File.ReadAllText(_stateFilePath);
                    _persistedStates = JsonSerializer.Deserialize<Dictionary<string, PcgObjectState>>(json) ?? new();
                }
            }
            catch
            {
                _persistedStates = new();
            }
        }

        private void PersistToDisk()
        {
            try
            {
                string json = JsonSerializer.Serialize(_persistedStates, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(_stateFilePath, json);
            }
            catch
            {
                // Log error
            }
        }
    }
}