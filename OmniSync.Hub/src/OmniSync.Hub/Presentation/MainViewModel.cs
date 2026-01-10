using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Windows.Threading; // For Dispatcher
using WpfApp = System.Windows.Application;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Logic.Services; // Ensure Logic.Services is included for ShutdownMode
using System.Windows.Forms; // For MessageBox
using System.Windows.Input;
using OmniSync.Hub.Infrastructure;

namespace OmniSync.Hub.Presentation
{
    public class MainViewModel : INotifyPropertyChanged
    {
        private readonly HubMonitorService _hubMonitorService;
        private readonly InputService _inputService;
        private readonly ShutdownService _shutdownService;
        private readonly RegistryService _registryService;
        private readonly HubSettingsService _settingsService;
        private readonly KeyboardHook _keyboardHook;

        private DispatcherTimer _uiUpdateTimer;
        private DispatcherTimer _longPressTimer;
        private bool _isLongPress;

        // --- Commands ---
        public ICommand ClearLogCommand { get; }
        public ICommand AddMappingCommand { get; }
        public ICommand DeleteMappingCommand { get; }
        public ICommand StartRecordingHotkeyCommand { get; }
        public ICommand AddHotkeyCommand { get; }
        public ICommand DeleteHotkeyCommand { get; }
        public ICommand ScheduleShutdownCommand { get; }
        public ICommand ToggleShutdownModeCommand { get; }

        // --- Properties bound in XAML ---
        public ObservableCollection<string> ActiveConnections { get; }
        public ObservableCollection<string> LogMessages { get; }
        public string LogMessagesText => string.Join(Environment.NewLine, LogMessages);

        private string _lastIncomingCommand = "";
        public string LastIncomingCommand
        {
            get => _lastIncomingCommand;
            set
            {
                if (_lastIncomingCommand != value)
                {
                    _lastIncomingCommand = value;
                    OnPropertyChanged();
                }
            }
        }

        private bool _isRunOnStartupEnabled;
        public bool IsRunOnStartupEnabled
        {
            get => _isRunOnStartupEnabled;
            set
            {
                if (_isRunOnStartupEnabled != value)
                {
                    _isRunOnStartupEnabled = value;
                    _registryService.SetRunOnStartup(value); // Update registry
                    OnPropertyChanged();
                }
            }
        }

        private string _scheduledShutdownTimeLabel = "None";
        public string ScheduledShutdownTimeLabel
        {
            get => _scheduledShutdownTimeLabel;
            set
            {
                if (_scheduledShutdownTimeLabel != value)
                {
                    _scheduledShutdownTimeLabel = value;
                    OnPropertyChanged();
                }
            }
        }

        private string _shutdownModeLabel = "Shutdown";
        public string ShutdownModeLabel
        {
            get => _shutdownModeLabel;
            set
            {
                if (_shutdownModeLabel != value)
                {
                    _shutdownModeLabel = value;
                    OnPropertyChanged();
                }
            }
        }

        private string _newMappingKey = "";
        public string NewMappingKey
        {
            get => _newMappingKey;
            set
            {
                if (_newMappingKey != value)
                {
                    _newMappingKey = value;
                    OnPropertyChanged();
                }
            }
        }

        private string _newMappingPath = "";
        public string NewMappingPath
        {
            get => _newMappingPath;
            set
            {
                if (_newMappingPath != value)
                {
                    _newMappingPath = value;
                    OnPropertyChanged();
                }
            }
        }

        private string _newHotkeyName = "";
        public string NewHotkeyName
        {
            get => _newHotkeyName;
            set { if (_newHotkeyName != value) { _newHotkeyName = value; OnPropertyChanged(); } }
        }

        private string _newHotkeyAction = "";
        public string NewHotkeyAction
        {
            get => _newHotkeyAction;
            set { if (_newHotkeyAction != value) { _newHotkeyAction = value; OnPropertyChanged(); } }
        }

        private string _newHotkeyValue = "";
        public string NewHotkeyValue
        {
            get => _newHotkeyValue;
            set { if (_newHotkeyValue != value) { _newHotkeyValue = value; OnPropertyChanged(); } }
        }

        public ObservableCollection<KeyValuePair<string, string>> ExeMappings { get; }
        public ObservableCollection<HotkeyConfig> Hotkeys { get; }

        public List<string> AvailableActions { get; } = new()
        {
            "OPEN_HUB_WINDOW",
            "TOGGLE_MUTE",
            "VOL_UP",
            "VOL_DOWN",
            "SLEEP_PC",
            "SHUTDOWN_PC",
            "TFT_CLIPBOARD_MUST_INCLUDE_SOLVE_NEXT",
            "TFT_CLIPBOARD_CURRENT_TEAM",
            "TFT_COPY_SOLUTION_CODE",
            "SCREENSHOT"
        };

        private bool _isRecordingHotkey;
        public bool IsRecordingHotkey
        {
            get => _isRecordingHotkey;
            set 
            { 
                if (_isRecordingHotkey != value) 
                { 
                    _isRecordingHotkey = value; 
                    _keyboardHook.IsRecording = value;
                    OnPropertyChanged(); 
                } 
            }
        }

        private HotkeyConfig? _recordingTarget;
        private readonly HashSet<Keys> _currentlyPressedKeys = new();
        private string _lastCapturedCombo = "";
        private bool _hasNonModifierInCombo = false;

        public string ActiveWindowText { get; private set; } = "N/A";

        // Parameterless constructor for XAML/Design-time compatibility
        public MainViewModel()
        {
            ActiveConnections = new ObservableCollection<string>();
            LogMessages = new ObservableCollection<string>();
            ExeMappings = new ObservableCollection<KeyValuePair<string, string>>();
            Hotkeys = new ObservableCollection<HotkeyConfig>();

            ClearLogCommand = new RelayCommand(_ => { });
            AddMappingCommand = new RelayCommand(_ => { });
            DeleteMappingCommand = new RelayCommand(_ => { });
            StartRecordingHotkeyCommand = new RelayCommand(_ => { });
            AddHotkeyCommand = new RelayCommand(_ => { });
            DeleteHotkeyCommand = new RelayCommand(_ => { });
            ScheduleShutdownCommand = new RelayCommand(_ => { });
            ToggleShutdownModeCommand = new RelayCommand(_ => { });
        }

        // Modifier Key States
        private bool _isShiftPressed;
        public bool IsShiftPressed
        {
            get => _isShiftPressed;
            set { _isShiftPressed = value; OnPropertyChanged(); }
        }

        private bool _isCtrlPressed;
        public bool IsCtrlPressed
        {
            get => _isCtrlPressed;
            set { _isCtrlPressed = value; OnPropertyChanged(); }
        }

        private bool _isAltPressed;
        public bool IsAltPressed
        {
            get => _isAltPressed;
            set { _isAltPressed = value; OnPropertyChanged(); }
        }

        public MainViewModel(HubMonitorService hubMonitorService, InputService inputService, ShutdownService shutdownService, RegistryService registryService, HubSettingsService settingsService, KeyboardHook keyboardHook)
        {
            _hubMonitorService = hubMonitorService;
            _inputService = inputService;
            _shutdownService = shutdownService;
            _registryService = registryService;
            _settingsService = settingsService;
            _keyboardHook = keyboardHook;

            // Initialize collections
            ActiveConnections = _hubMonitorService.ActiveConnections;
            LogMessages = _hubMonitorService.LogMessages;
            LogMessages.CollectionChanged += (s, e) => OnPropertyChanged(nameof(LogMessagesText));
            ExeMappings = new ObservableCollection<KeyValuePair<string, string>>(_settingsService.Settings.ExeMappings.ToList());
            Hotkeys = new ObservableCollection<HotkeyConfig>(_settingsService.Settings.Hotkeys);

            // Hook up event handlers
            _hubMonitorService.PropertyChanged += (s, e) =>
            {
                if (e.PropertyName == nameof(_hubMonitorService.LastIncomingCommand))
                {
                    LastIncomingCommand = _hubMonitorService.LastIncomingCommand;
                }
            };
            _keyboardHook.KeyActionOccurred += OnGlobalKeyAction;
            _shutdownService.ShutdownScheduled += (s, scheduledTime) => UpdateShutdownLabel(scheduledTime);
            _inputService.ModifierStateChanged += (s, e) => UpdateModifierState(e.Modifier, e.IsPressed);
            _settingsService.SettingsChanged += (s, e) =>
            {
                // Refresh mappings when settings change
                WpfApp.Current?.Dispatcher.BeginInvoke(new Action(() => {
                    ExeMappings.Clear();
                    foreach (var mapping in _settingsService.Settings.ExeMappings)
                    {
                        ExeMappings.Add(mapping);
                    }
                    
                    Hotkeys.Clear();
                    foreach (var hk in _settingsService.Settings.Hotkeys)
                    {
                        Hotkeys.Add(hk);
                    }
                }));
            };

            // Initialize commands
            ClearLogCommand = new RelayCommand(_ => _hubMonitorService.ClearLog());
            AddMappingCommand = new RelayCommand(_ => ExecuteAddMapping());
            DeleteMappingCommand = new RelayCommand(p => ExecuteDeleteMapping(p as string));
            StartRecordingHotkeyCommand = new RelayCommand(p => ExecuteStartRecording(p as HotkeyConfig));
            AddHotkeyCommand = new RelayCommand(_ => ExecuteAddHotkey());
            DeleteHotkeyCommand = new RelayCommand(p => ExecuteDeleteHotkey(p as string));
            ScheduleShutdownCommand = new RelayCommand(_ => ExecuteScheduleShutdown());
            ToggleShutdownModeCommand = new RelayCommand(_ => ExecuteToggleShutdownMode());

            // Initialize timers
            _uiUpdateTimer = new DispatcherTimer();
            _uiUpdateTimer.Interval = TimeSpan.FromSeconds(1);
            _uiUpdateTimer.Tick += (s, e) => UpdateShutdownLabel(_shutdownService.GetScheduledTime());
            _uiUpdateTimer.Start();

            _longPressTimer = new DispatcherTimer();
            _longPressTimer.Interval = TimeSpan.FromMilliseconds(500);
            _longPressTimer.Tick += LongPressTimer_Tick;

            // Initial updates
            IsRunOnStartupEnabled = _registryService.IsRunOnStartupEnabled();
            UpdateShutdownLabel(_shutdownService.GetScheduledTime());
            ShutdownModeLabel = _shutdownService.GetCurrentMode().ToString();
            ActiveWindowText = _inputService.GetActiveWindowTitle(); // Assuming InputService can provide this
        }


        // --- Command Implementations ---
        private void ExecuteAddMapping()
        {
            if (string.IsNullOrEmpty(NewMappingKey) || string.IsNullOrEmpty(NewMappingPath))
            {
                MessageBox.Show("Please enter both a key and a full path.", "Missing Data", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            _settingsService.AddMapping(NewMappingKey, NewMappingPath);
            NewMappingKey = "";
            NewMappingPath = "";
            RefreshMappingsGrid();
        }

        private void ExecuteDeleteMapping(string? key)
        {
            if (key == null) return;
            _settingsService.RemoveMapping(key);
            RefreshMappingsGrid();
        }

        private void ExecuteStartRecording(HotkeyConfig? target)
        {
            _hubMonitorService.AddLogMessage($"HOTKEY_RECORDING_STARTED for: {(target?.Name ?? "New Hotkey Input")}");
            _recordingTarget = target;
            _currentlyPressedKeys.Clear();
            _lastCapturedCombo = "";
            _hasNonModifierInCombo = false;
            IsRecordingHotkey = true;
        }

        private void OnGlobalKeyAction(object? sender, KeyHookEventArgs e)
        {
            if (!IsRecordingHotkey) return;

            if (e.State == KeyState.Down)
            {
                _currentlyPressedKeys.Add(e.Key);
                
                if (!IsModifier(e.Key))
                {
                    _hasNonModifierInCombo = true;
                }

                // Build string from CURRENTLY pressed keys to be 100% sure
                var displayKeys = new List<string>();
                
                // Keep standard order: Ctrl, Alt, Shift, Win, then the key
                if (_currentlyPressedKeys.Any(k => k == Keys.ControlKey || k == Keys.LControlKey || k == Keys.RControlKey)) displayKeys.Add("Ctrl");
                if (_currentlyPressedKeys.Any(k => k == Keys.Menu || k == Keys.LMenu || k == Keys.RMenu)) displayKeys.Add("Alt");
                if (_currentlyPressedKeys.Any(k => k == Keys.ShiftKey || k == Keys.LShiftKey || k == Keys.RShiftKey)) displayKeys.Add("Shift");
                if (_currentlyPressedKeys.Any(k => k == Keys.LWin || k == Keys.RWin)) displayKeys.Add("Win");

                foreach (var k in _currentlyPressedKeys)
                {
                    if (!IsModifier(k))
                    {
                        string name = k.ToString().ToUpper();
                        if (name == "SPACE") name = "SPACE";
                        displayKeys.Add(name);
                    }
                }

                if (displayKeys.Count > 0)
                {
                    _lastCapturedCombo = string.Join("+", displayKeys.Distinct());
                    
                    // Update UI preview
                    WpfApp.Current?.Dispatcher.BeginInvoke(new Action(() => {
                        if (_recordingTarget != null) _recordingTarget.Key = _lastCapturedCombo + "...";
                        else NewHotkeyValue = _lastCapturedCombo + "...";
                    }));
                }
            }
            else // KeyUp
            {
                // If we are about to release the last key and we have a valid combo, finalize
                if (_currentlyPressedKeys.Count == 1 && _currentlyPressedKeys.Contains(e.Key) && _hasNonModifierInCombo && !string.IsNullOrEmpty(_lastCapturedCombo))
                {
                    string finalCombo = _lastCapturedCombo;
                    WpfApp.Current?.Dispatcher.BeginInvoke(new Action(() => {
                        _hubMonitorService.AddLogMessage($"Smart Capture Finished: {finalCombo}");
                        if (_recordingTarget != null)
                        {
                            _recordingTarget.Key = finalCombo;
                            _settingsService.SaveSettings();
                            RefreshHotkeysGrid();
                        }
                        else
                        {
                            NewHotkeyValue = finalCombo;
                        }
                        IsRecordingHotkey = false;
                        _recordingTarget = null;
                    }));
                }

                _currentlyPressedKeys.Remove(e.Key);
            }
        }

        private bool IsModifier(Keys k)
        {
            return k == Keys.ControlKey || k == Keys.LControlKey || k == Keys.RControlKey ||
                   k == Keys.ShiftKey || k == Keys.LShiftKey || k == Keys.RShiftKey ||
                   k == Keys.Menu || k == Keys.LMenu || k == Keys.RMenu ||
                   k == Keys.LWin || k == Keys.RWin;
        }

        private void ExecuteAddHotkey()
        {
            if (string.IsNullOrEmpty(NewHotkeyName) || string.IsNullOrEmpty(NewHotkeyAction))
            {
                MessageBox.Show("Please enter both a name and an action.", "Missing Data", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            
            _settingsService.AddHotkey(new HotkeyConfig { 
                Name = NewHotkeyName, 
                Action = NewHotkeyAction, 
                Key = NewHotkeyValue 
            });
            
            NewHotkeyName = "";
            NewHotkeyAction = "";
            NewHotkeyValue = "";
            RefreshHotkeysGrid();
        }

        private void ExecuteDeleteHotkey(string? name)
        {
            if (name == null) return;
            _settingsService.RemoveHotkey(name);
            RefreshHotkeysGrid();
        }

        private void ExecuteToggleShutdownMode()
        {
            var currentMode = _shutdownService.GetCurrentMode();
            var newMode = currentMode == ShutdownMode.Shutdown
                ? ShutdownMode.Sleep
                : ShutdownMode.Shutdown;
            _shutdownService.SetMode(newMode);
            ShutdownModeLabel = newMode.ToString();
        }

        private int _shutdownIndex = 0;
        private int[] _shutdownTimes = { 0, 15, 30, 60, 120, 300, 720 }; // Minutes

        private void ExecuteScheduleShutdown()
        {
            _shutdownIndex = (_shutdownIndex + 1) % _shutdownTimes.Length;
            int minutes = _shutdownTimes[_shutdownIndex];
            _shutdownService.ScheduleShutdown(minutes);
        }

        public void StartLongPressTimer()
        {
            _isLongPress = false;
            _longPressTimer.Start();
        }

        public void StopLongPressTimer()
        {
            _longPressTimer.Stop();
            // Handle click only if it wasn't a long press
            if (!_isLongPress)
            {
                ExecuteScheduleShutdown();
            }
        }

        private void LongPressTimer_Tick(object? sender, EventArgs e)
        {
            _longPressTimer.Stop();
            _isLongPress = true;
            ExecuteToggleShutdownMode(); // Toggle mode on long press
        }

        // --- Event Handlers from Services ---
        public void UpdateModifierState(ModifierKey modifier, bool isPressed)
        {
            switch (modifier)
            {
                case ModifierKey.Shift: IsShiftPressed = isPressed; break;
                case ModifierKey.Ctrl: IsCtrlPressed = isPressed; break;
                case ModifierKey.Alt: IsAltPressed = isPressed; break;
            }
        }

        public void UpdateShutdownLabel(DateTime? scheduledTime)
        {
            ShutdownModeLabel = _shutdownService.GetCurrentMode().ToString();
            if (scheduledTime == null)
            {
                ScheduledShutdownTimeLabel = "None";
            }
            else
            {
                var remaining = scheduledTime.Value - DateTime.Now;
                if (remaining.TotalSeconds > 0)
                {
                    if (remaining.TotalHours >= 1)
                    {
                        ScheduledShutdownTimeLabel = $"{remaining.Days}d {(int)remaining.TotalHours % 24}h {remaining.Minutes}m {remaining.Seconds}s";
                    }
                    else
                    {
                        ScheduledShutdownTimeLabel = $"{remaining.Minutes}m {remaining.Seconds}s";
                    }
                }
                else
                {
                    ScheduledShutdownTimeLabel = "Now";
                }
            }
        }
        
        // --- Helper to refresh grid binding ---
        public void RefreshMappingsGrid()
        {
            ExeMappings.Clear();
            foreach(var mapping in _settingsService.Settings.ExeMappings)
            {
                ExeMappings.Add(mapping);
            }
        }

        public void RefreshHotkeysGrid()
        {
            Hotkeys.Clear();
            foreach (var hk in _settingsService.Settings.Hotkeys)
            {
                Hotkeys.Add(hk);
            }
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }
}