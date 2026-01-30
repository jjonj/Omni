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
        private bool _tftAddModeActive = false;

        public event EventHandler? OpenHubWindowRequested;
        public event EventHandler? ShowProjectSelectorRequested;

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
            if (e.State == KeyState.Down)
            {
                _pressedKeys.Add(e.Key);
            }
            else
            {
                _pressedKeys.Remove(e.Key);

                // Extra safety: if any modifier key is released, clear our internal tracking for it entirely
                // This helps recover from "stuck" modifiers if a KeyUp event was missed previously
                if (e.Key == Keys.LMenu || e.Key == Keys.Menu)
                {
                    _pressedKeys.Remove(Keys.LMenu);
                    _pressedKeys.Remove(Keys.Menu);
                }
                if (e.Key == Keys.LControlKey || e.Key == Keys.ControlKey)
                {
                    _pressedKeys.Remove(Keys.LControlKey);
                    _pressedKeys.Remove(Keys.ControlKey);
                }
                if (e.Key == Keys.LShiftKey || e.Key == Keys.ShiftKey)
                {
                    _pressedKeys.Remove(Keys.LShiftKey);
                    _pressedKeys.Remove(Keys.ShiftKey);
                }
            }

            // Real-time synchronization: if GetAsyncKeyState says a modifier is NOT pressed, 
            // ensure it's removed from our tracked set regardless of previous KeyUp events.
            if (!e.Alt)
            {
                _pressedKeys.Remove(Keys.LMenu);
                _pressedKeys.Remove(Keys.Menu);
            }
            if (!e.Control)
            {
                _pressedKeys.Remove(Keys.LControlKey);
                _pressedKeys.Remove(Keys.ControlKey);
            }
            if (!e.Shift)
            {
                _pressedKeys.Remove(Keys.LShiftKey);
                _pressedKeys.Remove(Keys.ShiftKey);
            }

            if (e.State != KeyState.Down) return;

            // Debug log every key press to monitor service
            // _hubMonitorService.AddLogMessage($"DEBUG: KeyDown={e.Key}, S={e.Shift}, C={e.Control}, A={e.Alt}, W={e.Win}");

            // Don't trigger if we are in recording mode (MainViewModel handles that)
            if (_keyboardHook.IsRecording) return;

            // Check if TFT is active
            bool isTftActive = _hubMonitorService.IsTftActive;

            // Check if this key combo is the toggle for Add Mode
            var addModeHotkey = _settingsService.Settings.Hotkeys.FirstOrDefault(h => h.Action == "TFT_ENTER_ADD_MODE");
            bool isToggleKey = addModeHotkey != null && MatchHotkey(addModeHotkey.Key, e);

            if (isToggleKey)
            {
                // Only allow toggling Add Mode if TFT is active
                if (!isTftActive)
                {
                    return; 
                }

                _tftAddModeActive = !_tftAddModeActive;
                _hubMonitorService.AddLogMessage($"[TFT] Active Mode Toggle: {_tftAddModeActive}");
                ExecuteHotkeyAction("TFT_ENTER_ADD_MODE", new { Active = _tftAddModeActive });
                // User requested NOT to consume the key
                e.Handled = false; 
                return;
            }

            // If Add Mode is active
            if (_tftAddModeActive)
            {
                // Safety: If TFT became inactive, force disable Add Mode immediately and let key pass
                if (!isTftActive)
                {
                    _tftAddModeActive = false;
                    _hubMonitorService.AddLogMessage("[TFT] Active Mode disabled (Tab lost focus).");
                    ExecuteHotkeyAction("TFT_ENTER_ADD_MODE", new { Active = false }); // Signal exit to UI
                    return; // Let key pass through
                }

                // Check registered hotkeys first (e.g., Alt+Return, Tab)
                var tftHotkeys = _settingsService.Settings.Hotkeys.Where(h => h.Action.StartsWith("TFT_")).ToList();
                // _hubMonitorService.AddLogMessage($"[TFT-Debug] Checking {tftHotkeys.Count} TFT hotkeys...");

                foreach (var hotkey in tftHotkeys)
                {
                    if (string.IsNullOrEmpty(hotkey.Key)) continue;

                    if (MatchHotkey(hotkey.Key, e))
                    {
                        _hubMonitorService.AddLogMessage($"[TFT] Hotkey Triggered: {hotkey.Name}");
                        ExecuteHotkeyAction(hotkey.Action);
                        
                        // Per user request: Do NOT consume the keys, just detect them.
                        // This allows the hotkey to reach the active application as well.
                        e.Handled = false; 
                        
                        // If it was Ctrl+Tab, we manually set Handled to true because we don't want browser to switch tabs
                        if (e.Control && e.Key == Keys.Tab) e.Handled = true;
                        
                        return;
                    }
                }

                // Fallback to typing capture - ONLY if no modifiers (except Shift) are pressed
                // We check both the hook flags AND our tracked keys for safety
                bool hasCtrl = e.Control || _pressedKeys.Any(k => k == Keys.ControlKey || k == Keys.LControlKey || k == Keys.RControlKey);
                bool hasAlt = e.Alt || _pressedKeys.Any(k => k == Keys.Menu || k == Keys.LMenu || k == Keys.RMenu);
                bool hasWin = e.Win || _pressedKeys.Any(k => k == Keys.LWin || k == Keys.RWin);

                if (!hasCtrl && !hasAlt && !hasWin && IsTypingKey(e.Key))
                {
                    string keyStr = GetKeyString(e);
                    if (!string.IsNullOrEmpty(keyStr))
                    {
                        var payload = new { Key = keyStr };
                        _commandDispatcher.Dispatch("TFT_INPUT", payload);
                        e.Handled = true;
                        return;
                    }
                }
                else if (e.Key == Keys.Escape)
                {
                    // Safety escape
                    _tftAddModeActive = false;
                    ExecuteHotkeyAction("TFT_ENTER_ADD_MODE", new { Active = false }); // Signal exit
                    e.Handled = true;
                    return;
                }
            }

            // If NOT in Add Mode, we only allow standard Hub hotkeys (non-TFT)
            foreach (var hotkey in _settingsService.Settings.Hotkeys)
            {
                if (string.IsNullOrEmpty(hotkey.Key)) continue;
                if (hotkey.Action.StartsWith("TFT_")) continue; // Skip TFT hotkeys if not in Add Mode

                if (MatchHotkey(hotkey.Key, e))
                {
                    _hubMonitorService.AddLogMessage($"[Hub] Hotkey Triggered: {hotkey.Name}");
                    ExecuteHotkeyAction(hotkey.Action);
                }
            }
        }

        private bool IsTypingKey(Keys k)
        {
            // A-Z, 0-9, Backspace, Enter, Tab
            return (k >= Keys.A && k <= Keys.Z) || 
                   (k >= Keys.D0 && k <= Keys.D9) || 
                   (k >= Keys.NumPad0 && k <= Keys.NumPad9) ||
                   k == Keys.Back || k == Keys.Return || k == Keys.Enter || k == Keys.Tab;
        }

        private string GetKeyString(KeyHookEventArgs e)
        {
            if (e.Key == Keys.Back) return "Backspace";
            if (e.Key == Keys.Return || e.Key == Keys.Enter) return "Enter";
            if (e.Key == Keys.Tab) return "Tab";
            
            string s = e.Key.ToString();
            if (s.Length == 1) return s;
            if (s.StartsWith("D") && s.Length == 2 && char.IsDigit(s[1])) return s.Substring(1);
            if (s.StartsWith("NumPad") && s.Length == 7 && char.IsDigit(s[6])) return s.Substring(6);
            
            return "";
        }

        private bool MatchHotkey(string hotkeyStr, KeyHookEventArgs e)
        {
            if (string.IsNullOrWhiteSpace(hotkeyStr)) return false;

            // Support multiple separators (+, -, space)
            var parts = hotkeyStr.Split(new[] { '+', '-', ' ' }, StringSplitOptions.RemoveEmptyEntries)
                                 .Select(p => p.Trim().ToUpper())
                                 .ToList();

            if (parts.Count == 0) return false;

            // Required modifiers from the config string
            bool ctrlReq = parts.Contains("CTRL") || parts.Contains("CONTROL");
            bool altReq = parts.Contains("ALT") || parts.Contains("MENU");
            bool shiftReq = parts.Contains("SHIFT");
            bool winReq = parts.Contains("WIN") || parts.Contains("CMD");

            // Check modifier states using both hook flags AND tracked keys for maximum reliability
            bool isCtrl = e.Control || _pressedKeys.Any(k => k == Keys.ControlKey || k == Keys.LControlKey);
            bool isAlt = e.Alt || _pressedKeys.Any(k => k == Keys.Menu || k == Keys.LMenu);
            bool isShift = e.Shift || _pressedKeys.Any(k => k == Keys.ShiftKey || k == Keys.LShiftKey);
            bool isWin = e.Win || _pressedKeys.Any(k => k == Keys.LWin);

            string targetKeyName = parts.Last();
            string pressedKeyName = e.Key.ToString().ToUpper();

            // Match logic
            bool modifiersMatch = (ctrlReq == isCtrl && altReq == isAlt && shiftReq == isShift && winReq == isWin);
            bool keyMatch = false;

            // Special cases
            if (targetKeyName == "SPACE" && e.Key == Keys.Space) keyMatch = true;
            else if (targetKeyName == "RETURN" && e.Key == Keys.Return) keyMatch = true;
            else if (targetKeyName == "ENTER" && e.Key == Keys.Enter) keyMatch = true;
            else if (targetKeyName == "TAB" && e.Key == Keys.Tab) keyMatch = true;
            else if (targetKeyName.Length == 1 && char.IsDigit(targetKeyName[0]) && (pressedKeyName == "D" + targetKeyName || pressedKeyName == "NUMPAD" + targetKeyName)) keyMatch = true;
            else if (targetKeyName == pressedKeyName) keyMatch = true;
            
            bool result = modifiersMatch && keyMatch && !IsModifier(e.Key);

            if (keyMatch && !result && !IsModifier(e.Key))
            {
                // Detailed logging for mismatched hotkeys to help debug "stuck" modifiers or incorrect configs
                // _hubMonitorService.AddLogMessage($"[Hotkey] '{hotkeyStr}' key match but modifier mismatch. Req: C={ctrlReq},A={altReq},S={shiftReq},W={winReq}. Actual: C={isCtrl},A={isAlt},S={isShift},W={isWin}. Flags: C={(e.Control)},A={(e.Alt)}");
            }

            return result;
        }

        private bool IsModifier(Keys k)
        {
            return k == Keys.ControlKey || k == Keys.LControlKey ||
                   k == Keys.ShiftKey || k == Keys.LShiftKey ||
                   k == Keys.Menu || k == Keys.LMenu ||
                   k == Keys.LWin;
        }

        private void ExecuteHotkeyAction(string action, object? extraPayload = null)
        {
            if (action == "OPEN_HUB_WINDOW")
            {
                // We need to ensure this event is raised, and the receiver (TrayIconManager) handles thread switching
                OpenHubWindowRequested?.Invoke(this, EventArgs.Empty);
                return;
            }

            if (action == "SHOW_PROJECT_SELECTOR")
            {
                ShowProjectSelectorRequested?.Invoke(this, EventArgs.Empty);
                return;
            }

            try
            {
                if (action.StartsWith("LAUNCH_PROJECT_"))
                {
                    var idStr = action.Substring("LAUNCH_PROJECT_".Length);
                    var payload = new { Id = idStr };
                    _commandDispatcher.Dispatch("LAUNCH_PROJECT", payload);
                }
                else
                {
                    // Dispatch through CommandDispatcher
                    _commandDispatcher.Dispatch(action, extraPayload ?? new { });
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error executing hotkey action: {action}");
            }
        }

        public void RequestOpenWindow()
        {
            OpenHubWindowRequested?.Invoke(this, EventArgs.Empty);
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
