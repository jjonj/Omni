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

        private readonly HashSet<Keys> _pressedKeys = new();

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
            // Track state for both Down and Up to be accurate
            if (e.State == KeyState.Down) _pressedKeys.Add(e.Key);
            else _pressedKeys.Remove(e.Key);

            if (e.State != KeyState.Down) return;

            // Don't trigger if we are in recording mode (MainViewModel handles that)
            if (_keyboardHook.IsRecording) return;

            foreach (var hotkey in _settingsService.Settings.Hotkeys)
            {
                if (string.IsNullOrEmpty(hotkey.Key)) continue;

                if (MatchHotkey(hotkey.Key, e))
                {
                    _logger.LogInformation($"[GlobalHotkeyService] Triggered: {hotkey.Name} ({hotkey.Key})");
                    _hubMonitorService.AddLogMessage($"Hotkey triggered: {hotkey.Name}");
                    ExecuteHotkeyAction(hotkey.Action);
                }
            }
        }

        private bool MatchHotkey(string hotkeyStr, KeyHookEventArgs e)
        {
            var parts = hotkeyStr.Split('+').Select(p => p.Trim().ToUpper()).ToList();
            
            // Required modifiers from the config string
            bool ctrlReq = parts.Contains("CTRL");
            bool altReq = parts.Contains("ALT");
            bool shiftReq = parts.Contains("SHIFT");
            bool winReq = parts.Contains("WIN");

            // Current modifier state (use tracked keys for reliability)
            bool isCtrl = _pressedKeys.Any(k => k == Keys.ControlKey || k == Keys.LControlKey || k == Keys.RControlKey);
            bool isAlt = _pressedKeys.Any(k => k == Keys.Menu || k == Keys.LMenu || k == Keys.RMenu);
            bool isShift = _pressedKeys.Any(k => k == Keys.ShiftKey || k == Keys.LShiftKey || k == Keys.RShiftKey);
            bool isWin = _pressedKeys.Any(k => k == Keys.LWin || k == Keys.RWin);

            if (ctrlReq != isCtrl || altReq != isAlt || shiftReq != isShift || winReq != isWin) return false;

            string targetKeyName = parts.Last();
            string pressedKeyName = e.Key.ToString().ToUpper();

            // Special cases
            if (targetKeyName == "SPACE" && e.Key == Keys.Space) return true;
            
            // If the pressed key IS a modifier, we don't trigger (Wait for the actual key)
            if (IsModifier(e.Key)) return false;

            return targetKeyName == pressedKeyName;
        }

        private bool IsModifier(Keys k)
        {
            return k == Keys.ControlKey || k == Keys.LControlKey || k == Keys.RControlKey ||
                   k == Keys.ShiftKey || k == Keys.LShiftKey || k == Keys.RShiftKey ||
                   k == Keys.Menu || k == Keys.LMenu || k == Keys.RMenu ||
                   k == Keys.LWin || k == Keys.RWin;
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
                if (action.StartsWith("LAUNCH_PROJECT_"))
                {
                    var idStr = action.Substring("LAUNCH_PROJECT_".Length);
                    var payload = new { Id = idStr };
                    var json = System.Text.Json.JsonSerializer.Serialize(payload);
                    using var doc = System.Text.Json.JsonDocument.Parse(json);
                    _commandDispatcher.Dispatch("LAUNCH_PROJECT", doc.RootElement);
                }
                else
                {
                    // Dispatch through CommandDispatcher
                    // We create a minimal JSON object: {}
                    using var doc = System.Text.Json.JsonDocument.Parse("{}");
                    _commandDispatcher.Dispatch(action, doc.RootElement);
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error executing hotkey action: {action}");
            }
        }

        public void Dispose()
        {
            if (_keyboardHook != null)
            {
                _keyboardHook.KeyActionOccurred -= OnKeyActionOccurred;
            }
        }
    }
}
