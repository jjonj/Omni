using System;
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
            set { if (_isRecordingHotkey != value) { _isRecordingHotkey = value; OnPropertyChanged(); } }
        }

        private HotkeyConfig? _recordingTarget;

        public string ActiveWindowText { get; private set; } = "N/A";

        // Parameterless constructor for XAML/Design-time compatibility
        public MainViewModel()
        {
            ActiveConnections = new ObservableCollection<string>();
            LogMessages = new ObservableCollection<string>();
            ExeMappings = new ObservableCollection<KeyValuePair<string, string>>();
            Hotkeys = new ObservableCollection<HotkeyConfig>();
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


        // --- Commands/Actions ---
        public void ClearLogCommand()
        {
            _hubMonitorService.ClearLog();
        }

        public void AddMappingCommand()
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

        public void DeleteMappingCommand(string key)
        {
            _settingsService.RemoveMapping(key);
            RefreshMappingsGrid();
        }

        public void StartRecordingHotkeyCommand(HotkeyConfig? target = null)
        {
            _recordingTarget = target;
            IsRecordingHotkey = true;
            // The actual recording is handled by listening to global keys while this flag is true
        }

        private void OnGlobalKeyAction(object? sender, KeyHookEventArgs e)
        {
            if (!IsRecordingHotkey || e.State != KeyState.Down) return;

            // Don't capture just modifiers
            if (e.Key == Keys.ControlKey || e.Key == Keys.ShiftKey || e.Key == Keys.Menu || e.Key == Keys.LWin || e.Key == Keys.RWin) return;

            var keys = new List<string>();
            if (e.Control) keys.Add("Ctrl");
            if (e.Alt) keys.Add("Alt");
            if (e.Shift) keys.Add("Shift");
            if (e.Win) keys.Add("Win");
            
            string keyName = e.Key.ToString().ToUpper();
            if (keyName == "SPACE") keyName = "SPACE";
            keys.Add(keyName);

            string hotkeyStr = string.Join("+", keys);

            WpfApp.Current?.Dispatcher.BeginInvoke(new Action(() => {
                if (_recordingTarget != null)
                {
                    _recordingTarget.Key = hotkeyStr;
                    _settingsService.SaveSettings(); // Persist change
                    RefreshHotkeysGrid();
                }
                else
                {
                    NewHotkeyValue = hotkeyStr;
                }
                IsRecordingHotkey = false;
                _recordingTarget = null;
            }));
        }

        public void AddHotkeyCommand()
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

        public void DeleteHotkeyCommand(string name)
        {
            _settingsService.RemoveHotkey(name);
            RefreshHotkeysGrid();
        }

        public void ToggleShutdownModeCommand()
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

        public void ScheduleShutdownCommand()
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
                ScheduleShutdownCommand();
            }
        }

        private void LongPressTimer_Tick(object? sender, EventArgs e)
        {
            _longPressTimer.Stop();
            _isLongPress = true;
            ToggleShutdownModeCommand(); // Toggle mode on long press
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