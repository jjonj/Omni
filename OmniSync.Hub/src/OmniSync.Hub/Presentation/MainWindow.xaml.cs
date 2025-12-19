using System.Windows;
using System.Collections.ObjectModel; // Still needed for ObservableCollection type reference in XAML binding
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Threading; // For Dispatcher
using OmniSync.Hub.Logic.Monitoring; // For HubMonitorService
using System; // For Environment.NewLine
using OmniSync.Hub.Infrastructure.Services; // Add this using directive
using OmniSync.Hub.Logic.Services;
using Microsoft.Win32; // Added for Registry access

namespace OmniSync.Hub.Presentation
{
    public partial class MainWindow : Window, INotifyPropertyChanged
    {
        public event PropertyChangedEventHandler? PropertyChanged; // Still implement for self if needed, but DataContext will notify

        private readonly HubMonitorService _hubMonitorService;
        private readonly InputService _inputService; // Add InputService
        private readonly ShutdownService _shutdownService;
        private readonly RegistryService _registryService;
        private const string AppName = "OmniSync Hub";

        private int _shutdownIndex = 0;
        private readonly int[] _shutdownTimes = { 0, 15, 30, 60, 120, 300, 720 };
        private readonly string[] _shutdownLabels = { "None", "15m", "30m", "1h", "2h", "5h", "12h" };
        private readonly DispatcherTimer _uiUpdateTimer;
        private readonly DispatcherTimer _longPressTimer;
        private bool _isLongPress = false;

        public MainWindow(HubMonitorService hubMonitorService, InputService inputService, ShutdownService shutdownService, RegistryService registryService) // Add ShutdownService
        {
            InitializeComponent();
            _hubMonitorService = hubMonitorService;
            _inputService = inputService; // Assign InputService
            _shutdownService = shutdownService;
            _registryService = registryService;
            this.DataContext = _hubMonitorService; // Set DataContext to the HubMonitorService

            // Subscribe to HubMonitorService events
            _hubMonitorService.LogEntryAdded += HubMonitorService_LogEntryAdded;
            _shutdownService.ShutdownScheduled += ShutdownService_ShutdownScheduled;
            _shutdownService.ModeChanged += (s, e) => Dispatcher.BeginInvoke(() => UpdateShutdownButtonLabel(_shutdownService.GetScheduledTime()));

            // Initialize UI elements with current data from HubMonitorService
            // ConnectionsListBox is bound directly to ActiveConnections in XAML and ObservableCollection handles updates
            // LastCommandTextBlock.Text is bound to LastIncomingCommand
            
            // Initial load for LogTextBox
            foreach (var logEntry in _hubMonitorService.LogMessages)
            {
                LogTextBox.AppendText(logEntry + Environment.NewLine);
            }

            // Initial load for ShutdownButton
            UpdateShutdownButtonLabel(_shutdownService.GetScheduledTime());

            // Scroll to end of log on initial load
            LogTextBox.ScrollToEnd();

            // Initialize RunOnStartup checkbox state
            RunOnStartupCheckBox.IsChecked = _registryService.IsRunOnStartupEnabled();
            RunOnStartupCheckBox.Checked += RunOnStartupCheckBox_Checked;
            RunOnStartupCheckBox.Unchecked += RunOnStartupCheckBox_Unchecked;

            // Initialize and start UI update timer
            _uiUpdateTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(1)
            };
            _uiUpdateTimer.Tick += UiUpdateTimer_Tick;
            _uiUpdateTimer.Start();

            // Long press timer for button
            _longPressTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
            _longPressTimer.Tick += LongPressTimer_Tick;

            ShutdownButton.PreviewMouseDown += ShutdownButton_MouseDown;
            ShutdownButton.PreviewMouseUp += ShutdownButton_MouseUp;
            ShutdownButton.MouseLeave += (s, e) => _longPressTimer.Stop();
        }

        private void ShutdownButton_MouseDown(object sender, System.Windows.Input.MouseButtonEventArgs e)
        {
            _isLongPress = false;
            _longPressTimer.Start();
        }

        private void ShutdownButton_MouseUp(object sender, System.Windows.Input.MouseButtonEventArgs e)
        {
            _longPressTimer.Stop();
            if (_isLongPress)
            {
                e.Handled = true; // Prevent Click event
            }
        }

        private void LongPressTimer_Tick(object? sender, EventArgs e)
        {
            _longPressTimer.Stop();
            _isLongPress = true;
            
            // Toggle mode
            var currentMode = _shutdownService.GetCurrentMode();
            var newMode = currentMode == ShutdownMode.Shutdown ? ShutdownMode.Sleep : ShutdownMode.Shutdown;
            _shutdownService.SetMode(newMode);
        }

        private void UiUpdateTimer_Tick(object? sender, EventArgs e)
        {
            UpdateShutdownButtonLabel(_shutdownService.GetScheduledTime());
        }

        private void RunOnStartupCheckBox_Checked(object sender, RoutedEventArgs e)
        {
            _registryService.SetRunOnStartup(true);
        }

        private void RunOnStartupCheckBox_Unchecked(object sender, RoutedEventArgs e)
        {
            _registryService.SetRunOnStartup(false);
        }

        private void HubMonitorService_LogEntryAdded(object? sender, string message)
        {
            Dispatcher.BeginInvoke(() =>
            {
                LogTextBox.AppendText(message + Environment.NewLine);
                LogTextBox.ScrollToEnd();
            });
        }

        // We can keep OnPropertyChanged for future use if MainWindow itself needs to raise property changes
        protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }

        private void ShutdownService_ShutdownScheduled(object? sender, DateTime? scheduledTime)
        {
            Dispatcher.BeginInvoke(() =>
            {
                UpdateShutdownButtonLabel(scheduledTime);
            });
        }

        private void UpdateShutdownButtonLabel(DateTime? scheduledTime)
        {
            string modeLabel = _shutdownService.GetCurrentMode() == ShutdownMode.Shutdown ? "Shutdown" : "Sleep";

            if (scheduledTime == null)
            {
                ShutdownButton.Content = $"{modeLabel}: None";
                _shutdownIndex = 0;
            }
            else
            {
                var remaining = scheduledTime.Value - DateTime.Now;
                if (remaining.TotalSeconds > 0)
                {
                    if (remaining.TotalHours >= 1)
                    {
                        ShutdownButton.Content = $"{modeLabel}: {(int)remaining.TotalHours}h {remaining.Minutes}m {remaining.Seconds}s";
                    }
                    else
                    {
                        ShutdownButton.Content = $"{modeLabel}: {remaining.Minutes}m {remaining.Seconds}s";
                    }
                }
                else
                {
                    ShutdownButton.Content = $"{modeLabel}: Now";
                }
            }
        }

        private void ShutdownButton_Click(object sender, RoutedEventArgs e)
        {
            if (_isLongPress) return;

            _shutdownIndex = (_shutdownIndex + 1) % _shutdownTimes.Length;
            int minutes = _shutdownTimes[_shutdownIndex];
            _shutdownService.ScheduleShutdown(minutes);
        }
    }
}
