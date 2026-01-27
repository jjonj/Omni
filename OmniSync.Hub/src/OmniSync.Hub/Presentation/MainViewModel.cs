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
using OmniSync.Hub.Logic;
using System.Windows.Forms; // For MessageBox
using System.Windows.Input;
using OmniSync.Hub.Infrastructure;

namespace OmniSync.Hub.Presentation
{
    public class MainViewModel : INotifyPropertyChanged
    {
        private readonly HubMonitorService _hubMonitorService;
        private readonly InputService _inputService;
        private readonly ProcessService _processService;
        private readonly ShutdownService _shutdownService;
        private readonly RegistryService _registryService;
        private readonly HubSettingsService _settingsService;
        private readonly KeyboardHook _keyboardHook;
        private readonly AiCliService _aiCliService;
        private readonly LayoutCaptureService _layoutCaptureService;
        private readonly ProjectLauncherService _projectLauncherService;
        private readonly DispatcherTimer _uiUpdateTimer;
        private DispatcherTimer _longPressTimer;
        private bool _isLongPress;

        // --- Commands ---
        public ICommand ClearLogCommand { get; }
        public ICommand AddMappingCommand { get; }
        public ICommand DeleteMappingCommand { get; }
        public ICommand TestMappingCommand { get; }
        public ICommand StartRecordingHotkeyCommand { get; }
        public ICommand AddHotkeyCommand { get; }
        public ICommand DeleteHotkeyCommand { get; }
        public ICommand ScheduleShutdownCommand { get; }
        public ICommand ToggleShutdownModeCommand { get; }
        public ICommand FocusAiSessionsCommand { get; }
        public ICommand ResetAiSessionsCommand { get; }
        public ICommand SaveAiSettingsCommand { get; }
        public ICommand LaunchJarvisCommand { get; }

        public ICommand AddProjectCommand { get; }
        public ICommand DeleteProjectCommand { get; }
        public ICommand EditProjectCommand { get; }
        public ICommand SaveProjectCommand { get; }
        public ICommand AddProjectActionCommand { get; }
        public ICommand DeleteProjectActionCommand { get; }
        public ICommand CaptureLayoutCommand { get; }
        public ICommand LaunchProjectCommand { get; }

        // --- Properties bound in XAML ---
        public ObservableCollection<string> ActiveConnections { get; }
        public ObservableCollection<string> LogMessages { get; }
        public string LogMessagesText => string.Join(Environment.NewLine, LogMessages);

        public ObservableCollection<Project> Projects { get; }
        
        public List<string> AvailableActions
        {
            get
            {
                var actions = new List<string>
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
                    "TFT_RUN_OPTIMIZATION",
                    "TFT_CLEAR_ALL",
                    "TFT_SAVE_COMP",
                    "TFT_SWITCH_TAB_SOLVER",
                    "TFT_SWITCH_TAB_QUIZ",
                    "TFT_SWITCH_TAB_DIRECTOR",
                    "TFT_SWITCH_TAB_CONFIG",
                    "TFT_TOGGLE_COST_1",
                    "TFT_TOGGLE_COST_2",
                    "TFT_TOGGLE_COST_3",
                    "TFT_TOGGLE_COST_4",
                    "TFT_TOGGLE_COST_5",
                    "TFT_CYCLE_LEVELS",
                    "TFT_TOGGLE_LEVEL_4",
                    "TFT_TOGGLE_LEVEL_5",
                    "TFT_TOGGLE_LEVEL_6",
                    "TFT_TOGGLE_LEVEL_7",
                    "TFT_TOGGLE_LEVEL_8",
                    "TFT_TOGGLE_LEVEL_9",
                    "TFT_TOGGLE_LEVEL_10",
                    "TFT_CYCLE_HEURISTICS",
                    "TFT_TOGGLE_SMART_SORT",
                    "TFT_TOGGLE_EXCLUDE_5_COSTS",
                    "TFT_TOGGLE_IMPROVE_MODE",
                    "TFT_ACTIVATE_CURRENT_TEAM",
                    "TFT_ACTIVATE_MUST_INCLUDE",
                    "TFT_ENTER_ADD_MODE",
                    "TFT_COPY_ACTIVE",
                    "TFT_PASTE_ACTIVE",
                    "TFT_CLEAR_ACTIVE",
                    "TFT_CYCLE_UNIT_HIGHLIGHT",
                    "TFT_CYCLE_RESULT_NEXT",
                    "TFT_POC",
                    "SCREENSHOT"
                };

                if (Projects != null)
                {
                    foreach (var p in Projects)
                    {
                        actions.Add($"LAUNCH_PROJECT_{p.Id}");
                    }
                                }
                                return actions;
                            }
                        }
                
                        private Project? _currentEditingProject;
                        public Project? CurrentEditingProject
                        {
                            get => _currentEditingProject;
                            set { _currentEditingProject = value; OnPropertyChanged(); OnPropertyChanged(nameof(IsEditingProject)); }
                        }
                        public bool IsEditingProject => CurrentEditingProject != null;
                
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

                        public bool UseOneCommander
                        {
                            get => _settingsService.Settings.UseOneCommander;
                            set
                            {
                                if (_settingsService.Settings.UseOneCommander != value)
                                {
                                    _settingsService.Settings.UseOneCommander = value;
                                    _settingsService.SaveSettings();
                                    OnPropertyChanged();
                                }
                            }
                        }

                        public bool AiDebugMode
                        {
                            get => _settingsService.Settings.AiDebugMode;
                            set
                            {
                                if (_settingsService.Settings.AiDebugMode != value)
                                {
                                    _settingsService.Settings.AiDebugMode = value;
                                    _settingsService.SaveSettings();
                                    OnPropertyChanged();
                                }
                            }
                        }

                        // --- Tell PC Settings ---
                        public string TellPcWorkspace
                        {
                            get => _settingsService.Settings.TellPcWorkspace;
                            set
                            {
                                if (_settingsService.Settings.TellPcWorkspace != value)
                                {
                                    _settingsService.Settings.TellPcWorkspace = value;
                                    OnPropertyChanged();
                                }
                            }
                        }

                        public string TellPcSystemContext
                        {
                            get => _settingsService.Settings.TellPcSystemContext;
                            set
                            {
                                if (_settingsService.Settings.TellPcSystemContext != value)
                                {
                                    _settingsService.Settings.TellPcSystemContext = value;
                                    OnPropertyChanged();
                                }
                            }
                        }

                                public bool TellPcSoundEnabled
                                {
                                    get => _settingsService.Settings.TellPcSoundEnabled;
                                    set
                                    {
                                        if (_settingsService.Settings.TellPcSoundEnabled != value)
                                        {
                                            _settingsService.Settings.TellPcSoundEnabled = value;
                                            OnPropertyChanged();
                                        }
                                    }
                                }
                        
                                // --- Jarvis Settings ---
                                public bool JarvisAutoStart
                                {
                                    get => _settingsService.Settings.JarvisAutoStart;
                                    set
                                    {
                                        if (_settingsService.Settings.JarvisAutoStart != value)
                                        {
                                            _settingsService.Settings.JarvisAutoStart = value;
                                            _settingsService.SaveSettings();
                                            OnPropertyChanged();
                                        }
                                    }
                                }
                        
                                        public string JarvisWorkspace
                        
                                        {
                        
                                            get => _settingsService.Settings.JarvisWorkspace;
                        
                                            set
                        
                                            {
                        
                                                if (_settingsService.Settings.JarvisWorkspace != value)
                        
                                                {
                        
                                                    _settingsService.Settings.JarvisWorkspace = value;
                        
                                                    _settingsService.SaveSettings();
                        
                                                    OnPropertyChanged();
                        
                                                }
                        
                                            }
                        
                                        }
                        
                                
                        
                                        public string JarvisSystemContextPath
                        
                                        {
                        
                                            get => _settingsService.Settings.JarvisSystemContextPath;
                        
                                            set
                        
                                            {
                        
                                                if (_settingsService.Settings.JarvisSystemContextPath != value)
                        
                                                {
                        
                                                    _settingsService.Settings.JarvisSystemContextPath = value;
                        
                                                    _settingsService.SaveSettings();
                        
                                                    OnPropertyChanged();
                        
                                                }
                        
                                            }
                        
                                        }
                        
                                
                        
                                        public string JarvisModel
                        
                                        {
                        
                                            get => _settingsService.Settings.JarvisModel;
                        
                                            set
                        
                                            {
                        
                                                if (_settingsService.Settings.JarvisModel != value)
                        
                                                {
                        
                                                    _settingsService.Settings.JarvisModel = value;
                        
                                                    _settingsService.SaveSettings();
                        
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
            Projects = new ObservableCollection<Project>();

            ClearLogCommand = new RelayCommand(_ => { });
            AddMappingCommand = new RelayCommand(_ => { });
            DeleteMappingCommand = new RelayCommand(_ => { });
            StartRecordingHotkeyCommand = new RelayCommand(_ => { });
            AddHotkeyCommand = new RelayCommand(_ => { });
            DeleteHotkeyCommand = new RelayCommand(_ => { });
            ScheduleShutdownCommand = new RelayCommand(_ => { });
            ToggleShutdownModeCommand = new RelayCommand(_ => { });
            FocusAiSessionsCommand = new RelayCommand(_ => { });
            ResetAiSessionsCommand = new RelayCommand(_ => { });
            SaveAiSettingsCommand = new RelayCommand(_ => { });
            LaunchJarvisCommand = new RelayCommand(_ => { });

            AddProjectCommand = new RelayCommand(_ => { });
            DeleteProjectCommand = new RelayCommand(_ => { });
            EditProjectCommand = new RelayCommand(_ => { });
            SaveProjectCommand = new RelayCommand(_ => { });
            AddProjectActionCommand = new RelayCommand(_ => { });
            DeleteProjectActionCommand = new RelayCommand(_ => { });
            CaptureLayoutCommand = new RelayCommand(_ => { });
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

        public MainViewModel(HubMonitorService hubMonitorService, InputService inputService, ProcessService processService, ShutdownService shutdownService, RegistryService registryService, HubSettingsService settingsService, KeyboardHook keyboardHook, AiCliService aiCliService, LayoutCaptureService layoutCaptureService, ProjectLauncherService projectLauncherService)
        {
            _hubMonitorService = hubMonitorService;
            _inputService = inputService;
            _processService = processService;
            _shutdownService = shutdownService;
            _registryService = registryService;
            _settingsService = settingsService;
            _keyboardHook = keyboardHook;
            _aiCliService = aiCliService;
            _layoutCaptureService = layoutCaptureService;
            _projectLauncherService = projectLauncherService;

            // Initialize collections
            ActiveConnections = _hubMonitorService.ActiveConnections;
            LogMessages = _hubMonitorService.LogMessages;
            LogMessages.CollectionChanged += (s, e) => OnPropertyChanged(nameof(LogMessagesText));
            ExeMappings = new ObservableCollection<KeyValuePair<string, string>>(_settingsService.Settings.ExeMappings.ToList());
            Hotkeys = new ObservableCollection<HotkeyConfig>(_settingsService.Settings.Hotkeys);
            Projects = new ObservableCollection<Project>(_settingsService.Settings.Projects);

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

                    Projects.Clear();
                    foreach (var p in _settingsService.Settings.Projects)
                    {
                        Projects.Add(p);
                    }
                }));
            };

            // Initialize commands
            ClearLogCommand = new RelayCommand(_ => _hubMonitorService.ClearLog());
            AddMappingCommand = new RelayCommand(_ => ExecuteAddMapping());
            DeleteMappingCommand = new RelayCommand(p => ExecuteDeleteMapping(p as string));
            TestMappingCommand = new RelayCommand(p => ExecuteTestMapping(p as string));
            StartRecordingHotkeyCommand = new RelayCommand(p => ExecuteStartRecording(p as HotkeyConfig));
            AddHotkeyCommand = new RelayCommand(_ => ExecuteAddHotkey());
            DeleteHotkeyCommand = new RelayCommand(p => ExecuteDeleteHotkey(p as string));
            ScheduleShutdownCommand = new RelayCommand(_ => ExecuteScheduleShutdown());
            ToggleShutdownModeCommand = new RelayCommand(_ => ExecuteToggleShutdownMode());
            FocusAiSessionsCommand = new RelayCommand(async _ => await ExecuteFocusAiSessions());
            ResetAiSessionsCommand = new RelayCommand(async _ => {
                _hubMonitorService.AddLogMessage("[AI] User requested manual reset of all AI sessions.");
                _aiCliService.KillAllGeminiProcesses();
                await _aiCliService.DiscoverSessionsAsync();
            });

            SaveAiSettingsCommand = new RelayCommand(_ => {
                _settingsService.SaveSettings();
                _hubMonitorService.AddLogMessage("[Settings] Saved AI Configuration.");
                MessageBox.Show("AI Configuration Saved.", "Settings", MessageBoxButtons.OK, MessageBoxIcon.Information);
            });

            LaunchJarvisCommand = new RelayCommand(async _ => {
                _hubMonitorService.AddLogMessage("[AI] User triggered Jarvis Loop launch.");
                string workspace = _settingsService.Settings.JarvisWorkspace;
                string systemPromptPath = _settingsService.Settings.JarvisSystemContextPath;
                string model = _settingsService.Settings.JarvisModel;

                string systemContext = "You are Jarvis.";
                try
                {
                    if (System.IO.File.Exists(systemPromptPath))
                    {
                        systemContext = System.IO.File.ReadAllText(systemPromptPath);
                    }
                }
                catch { }

                                        _hubMonitorService.AddLogMessage($"[AI] Launching Jarvis session in {workspace} (Model: {model})...");

                                        

                                        var pid = await _aiCliService.LaunchSessionAsync(workspace, null, model, systemPromptPath);

                            

                                        if (pid.HasValue)
                {
                    _hubMonitorService.AddLogMessage($"[AI] Jarvis started with PID {pid.Value}. Sending 'Begin'.");
                    _aiCliService.SetTellPcContext(pid.Value, systemContext);
                    
                    // Send the "Begin" message
                    _ = Task.Run(async () => {
                        await Task.Delay(2000); 
                        await _aiCliService.SendPromptAsync("Begin", pid.Value);
                    });
                }
                else
                {
                    _hubMonitorService.AddLogMessage("[AI] Failed to start Jarvis.");
                }
            });

            AddProjectCommand = new RelayCommand(_ => ExecuteAddProject());
            DeleteProjectCommand = new RelayCommand(p => ExecuteDeleteProject(p as Project));
            EditProjectCommand = new RelayCommand(p => ExecuteEditProject(p as Project));
            SaveProjectCommand = new RelayCommand(_ => ExecuteSaveProject());
            AddProjectActionCommand = new RelayCommand(_ => ExecuteAddProjectAction());
            DeleteProjectActionCommand = new RelayCommand(p => ExecuteDeleteProjectAction(p as ProjectAction));
            CaptureLayoutCommand = new RelayCommand(_ => ExecuteCaptureLayout());
            LaunchProjectCommand = new RelayCommand(async p => await ExecuteLaunchProject(p as Project));

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
        private async Task ExecuteFocusAiSessions()
        {
            var sessions = _aiCliService.GetActiveSessions();
            if (sessions.Count == 0)
            {
                _hubMonitorService.AddLogMessage("[FocusAiSessions] No active sessions found.");
                return;
            }

            foreach (var session in sessions)
            {
                _hubMonitorService.AddLogMessage($"[FocusAiSessions] Focusing session PID: {session.Pid} ({session.Name})");
                await _aiCliService.FocusSessionAsync(session.Pid);
                await Task.Delay(500); // Small delay to visualize the loop
            }
        }

        private void ExecuteAddMapping()
        {
            if (string.IsNullOrEmpty(NewMappingKey) || string.IsNullOrEmpty(NewMappingPath))
            {
                MessageBox.Show("Please enter both a key and a full path.", "Missing Data", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            
            _hubMonitorService.AddLogMessage($"[Settings] Adding mapping: {NewMappingKey} -> {NewMappingPath}");
            _settingsService.AddMapping(NewMappingKey, NewMappingPath);
            
            NewMappingKey = "";
            NewMappingPath = "";
            
            WpfApp.Current?.Dispatcher.BeginInvoke(new Action(() => {
                RefreshMappingsGrid();
            }));
        }

        private void ExecuteDeleteMapping(string? key)
        {
            if (key == null) return;
            _settingsService.RemoveMapping(key);
            RefreshMappingsGrid();
        }

        private void ExecuteTestMapping(string? key)
        {
            if (key == null) return;
            _hubMonitorService.AddLogMessage($"[Settings] Testing mapping: {key}");
            _ = _processService.ExecuteCommand(key);
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
            var mode = _shutdownService.GetCurrentMode().ToString();
            ShutdownModeLabel = mode;
            if (scheduledTime == null)
            {
                ScheduledShutdownTimeLabel = $"{mode}: None";
            }
            else
            {
                var remaining = scheduledTime.Value - DateTime.Now;
                if (remaining.TotalSeconds > 0)
                {
                    string timeStr;
                    if (remaining.TotalHours >= 1)
                    {
                        timeStr = $"{(int)remaining.TotalHours}h {remaining.Minutes}m {remaining.Seconds}s";
                    }
                    else
                    {
                        timeStr = $"{remaining.Minutes}m {remaining.Seconds}s";
                    }
                    ScheduledShutdownTimeLabel = $"{mode}: {timeStr}";
                }
                else
                {
                    ScheduledShutdownTimeLabel = $"{mode}: Now";
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

        private void ExecuteAddProject()
        {
            var newProject = new Project { Name = "New Project" };
            _settingsService.AddProject(newProject);
            // SettingsChanged event will refresh the Projects collection
            CurrentEditingProject = CloneProject(newProject);
        }

        private void ExecuteDeleteProject(Project? project)
        {
            if (project == null) return;

            var result = MessageBox.Show($"Are you sure you want to delete the project '{project.Name}'?", "Confirm Delete", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (result == DialogResult.Yes)
            {
                _settingsService.RemoveProject(project.Id);
                if (CurrentEditingProject?.Id == project.Id)
                {
                    CurrentEditingProject = null;
                }
            }
        }

        private void ExecuteEditProject(Project? project)
        {
            if (project == null) return;
            CurrentEditingProject = CloneProject(project);
        }

        private void ExecuteSaveProject()
        {
            if (CurrentEditingProject == null) return;
            _settingsService.UpdateProject(CurrentEditingProject);
            _hubMonitorService.AddLogMessage($"[Settings] Saved project: {CurrentEditingProject.Name}");
            OnPropertyChanged(nameof(AvailableActions));
        }

        private void ExecuteAddProjectAction()
        {
            if (CurrentEditingProject == null) return;
            CurrentEditingProject.Actions.Add(new ProjectAction { 
                Type = ProjectActionType.OpenFolder,
                Path = "C:\\",
                Layout = new WindowLayout()
            });
            // To notify UI that Actions collection changed if it's not Observable
            // If it's a List, we might need to refresh the property
            OnPropertyChanged(nameof(CurrentEditingProject));
        }

        private void ExecuteDeleteProjectAction(ProjectAction? action)
        {
            if (CurrentEditingProject == null || action == null) return;

            var result = MessageBox.Show("Delete this action?", "Confirm Delete", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (result == DialogResult.Yes)
            {
                CurrentEditingProject.Actions.Remove(action);
                OnPropertyChanged(nameof(CurrentEditingProject));
            }
        }

        private void ExecuteCaptureLayout()
        {
            if (CurrentEditingProject == null) return;

            var captured = _layoutCaptureService.CaptureCurrentLayout();
            if (captured.Count == 0)
            {
                MessageBox.Show("No suitable windows found to capture.", "Capture Empty", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            if (MessageBox.Show($"Found {captured.Count} windows. Add them to '{CurrentEditingProject.Name}'?", "Confirm Capture", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes)
            {
                foreach (var win in captured)
                {
                    CurrentEditingProject.Actions.Add(new ProjectAction
                    {
                        Type = win.Type,
                        Path = win.Path,
                        Layout = win.Layout
                    });
                }
                OnPropertyChanged(nameof(CurrentEditingProject));
            }
        }

        private async Task ExecuteLaunchProject(Project? project)
        {
            if (project == null) return;
            _hubMonitorService.AddLogMessage($"[Launcher] Launching project: {project.Name}");
            await _projectLauncherService.LaunchProject(project);
        }

        private Project CloneProject(Project source)
        {
            var json = System.Text.Json.JsonSerializer.Serialize(source);
            return System.Text.Json.JsonSerializer.Deserialize<Project>(json) ?? new Project();
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }
}