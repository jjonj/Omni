using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using System.Windows.Forms;

namespace OmniSync.Hub.Logic.Services
{
    public class GlobalHotkeyService : IHostedService, IDisposable
    {
        private readonly ILogger<GlobalHotkeyService> _logger;
        private readonly HubSettingsService _settingsService;
        private readonly InputService _inputService;
        private readonly CommandDispatcher _commandDispatcher;
        private readonly HubMonitorService _hubMonitorService;
        private readonly KeyboardHook _keyboardHook;

        public event EventHandler? OpenHubWindowRequested;

        public GlobalHotkeyService(
            ILogger<GlobalHotkeyService> logger,
            HubSettingsService settingsService,
            InputService inputService,
            CommandDispatcher commandDispatcher,
            HubMonitorService hubMonitorService,
            KeyboardHook keyboardHook)
        {
            _logger = logger;
            _settingsService = settingsService;
            _inputService = inputService;
            _commandDispatcher = commandDispatcher;
            _hubMonitorService = hubMonitorService;
            _keyboardHook = keyboardHook;
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _keyboardHook.KeyActionOccurred += OnKeyActionOccurred;
            _logger.LogInformation("GlobalHotkeyService started.");
            return Task.CompletedTask;
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            _keyboardHook.KeyActionOccurred -= OnKeyActionOccurred;
            _logger.LogInformation("GlobalHotkeyService stopped.");
            return Task.CompletedTask;
        }

        private void OnKeyActionOccurred(object? sender, KeyHookEventArgs e)
        {
            if (e.State != KeyState.Down) return;

            foreach (var hotkey in _settingsService.Settings.Hotkeys)
            {
                if (string.IsNullOrEmpty(hotkey.Key)) continue;

                if (MatchHotkey(hotkey.Key, e))
                {
                    _logger.LogInformation($"Hotkey triggered: {hotkey.Name} ({hotkey.Key})");
                    _hubMonitorService.AddLogMessage($"Hotkey triggered: {hotkey.Name}");
                    ExecuteHotkeyAction(hotkey.Action);
                }
            }
        }

        private bool MatchHotkey(string hotkeyStr, KeyHookEventArgs e)
        {
            var parts = hotkeyStr.Split('+').Select(p => p.Trim().ToUpper()).ToList();
            
            bool ctrlReq = parts.Contains("CTRL");
            bool altReq = parts.Contains("ALT");
            bool shiftReq = parts.Contains("SHIFT");
            bool winReq = parts.Contains("WIN");

            if (ctrlReq != e.Control) return false;
            if (altReq != e.Alt) return false;
            if (shiftReq != e.Shift) return false;
            if (winReq != e.Win) return false;

            string targetKey = parts.Last();
            string pressedKey = e.Key.ToString().ToUpper();

            // Special cases
            if (targetKey == "SPACE" && e.Key == Keys.Space) return true;
            
            return targetKey == pressedKey;
        }

        private void ExecuteHotkeyAction(string action)
        {
            if (action == "OPEN_HUB_WINDOW")
            {
                // We need to ensure this event is raised, and the receiver (TrayIconManager) handles thread switching
                OpenHubWindowRequested?.Invoke(this, EventArgs.Empty);
                return;
            }

            try
            {
                // Dispatch through CommandDispatcher
                // We create a minimal JSON object: {}
                using var doc = System.Text.Json.JsonDocument.Parse("{}");
                _commandDispatcher.Dispatch(action, doc.RootElement);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error executing hotkey action: {action}");
            }
        }

        public void Dispose()
        {
            _keyboardHook.KeyActionOccurred -= OnKeyActionOccurred;
        }
    }
}
