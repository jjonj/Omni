using System.Windows;
using System.Collections.ObjectModel; // Still needed for ObservableCollection type reference in XAML binding
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Threading; // For Dispatcher
using OmniSync.Hub.Logic.Monitoring; // For HubMonitorService
using System; // For Environment.NewLine
using OmniSync.Hub.Infrastructure.Services; // Add this using directive
using Microsoft.Win32; // Added for Registry access

namespace OmniSync.Hub.Presentation
{
    public partial class MainWindow : Window, INotifyPropertyChanged
    {
        public event PropertyChangedEventHandler? PropertyChanged; // Still implement for self if needed, but DataContext will notify

        private readonly HubMonitorService _hubMonitorService;
        private readonly InputService _inputService; // Add InputService
        private const string AppName = "OmniSync Hub";

        public MainWindow(HubMonitorService hubMonitorService, InputService inputService) // Add InputService to constructor
        {
            InitializeComponent();
            _hubMonitorService = hubMonitorService;
            _inputService = inputService; // Assign InputService
            this.DataContext = _hubMonitorService; // Set DataContext to the HubMonitorService

            // Subscribe to HubMonitorService events
            _hubMonitorService.LogEntryAdded += HubMonitorService_LogEntryAdded;
            _hubMonitorService.CommandUpdateOccurred += HubMonitorService_CommandUpdateOccurred;
            _hubMonitorService.ConnectionAdded += HubMonitorService_ConnectionAdded; // New subscription
            _hubMonitorService.ConnectionRemoved += HubMonitorService_ConnectionRemoved; // New subscription

            // Initialize UI elements with current data from HubMonitorService
            // ConnectionsListBox is bound directly to ActiveConnections in XAML and ObservableCollection handles updates
            // LogTextBox.Text and LastCommandTextBlock.Text will be updated via events and binding
            
            // Initial load for LogTextBox and LastCommandTextBlock
            foreach (var logEntry in _hubMonitorService.LogMessages)
            {
                LogTextBox.AppendText(logEntry + Environment.NewLine);
            }
            LastCommandTextBlock.Text = _hubMonitorService.LastIncomingCommand;

            // Scroll to end of log on initial load
            LogTextBox.ScrollToEnd();

            // Initialize RunOnStartup checkbox state
            RunOnStartupCheckBox.IsChecked = IsRunOnStartupEnabled();
            RunOnStartupCheckBox.Checked += RunOnStartupCheckBox_Checked;
            RunOnStartupCheckBox.Unchecked += RunOnStartupCheckBox_Unchecked;
        }

        private bool IsRunOnStartupEnabled()
        {
            using (RegistryKey? rk = Registry.CurrentUser.OpenSubKey("SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run", false))
            {
                return rk?.GetValue(AppName) != null;
            }
        }

        private void SetRunOnStartup(bool enable)
        {
            using (RegistryKey? rk = Registry.CurrentUser.OpenSubKey("SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run", true))
            {
                if (rk == null) return;

                if (enable)
                {
                    // Get the path to the current executable
                    string? executablePath = System.Reflection.Assembly.GetExecutingAssembly().Location;
                    if (executablePath != null)
                    {
                        rk.SetValue(AppName, executablePath);
                        _hubMonitorService.AddLogMessage($"Enabled '{AppName}' to run on startup.");
                    }
                }
                else
                {
                    rk.DeleteValue(AppName, false);
                    _hubMonitorService.AddLogMessage($"Disabled '{AppName}' from running on startup.");
                }
            }
        }

        private void RunOnStartupCheckBox_Checked(object sender, RoutedEventArgs e)
        {
            SetRunOnStartup(true);
        }

        private void RunOnStartupCheckBox_Unchecked(object sender, RoutedEventArgs e)
        {
            SetRunOnStartup(false);
        }

        private void HubMonitorService_LogEntryAdded(object? sender, string message)
        {
            Dispatcher.Invoke(() =>
            {
                LogTextBox.AppendText(message + Environment.NewLine);
                LogTextBox.ScrollToEnd();
            });
        }

        private void HubMonitorService_CommandUpdateOccurred(object? sender, string command)
        {
            Dispatcher.Invoke(() =>
            {
                LastCommandTextBlock.Text = command;
            });
        }

        private void HubMonitorService_ConnectionAdded(object? sender, string connectionId) // New handler
        {
            Dispatcher.Invoke(() =>
            {
                _hubMonitorService.ActiveConnections.Add(connectionId);
            });
        }

        private void HubMonitorService_ConnectionRemoved(object? sender, string connectionId) // New handler
        {
            Dispatcher.Invoke(() =>
            {
                _hubMonitorService.ActiveConnections.Remove(connectionId);
            });
        }

        // We can keep OnPropertyChanged for future use if MainWindow itself needs to raise property changes
        protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }

        private void TestInput_Click(object sender, RoutedEventArgs e)
        {
            _hubMonitorService.AddLogMessage("[MainWindow] Testing keyboard input...");
            _inputService.SendKeyPress(0x41); // Press 'A'

            _hubMonitorService.AddLogMessage("[MainWindow] Testing mouse movement to (500,500)...");
            _inputService.MoveMouse(500, 500); // Move to a specific coordinate

            _hubMonitorService.AddLogMessage("[MainWindow] Input test sent.");
        }
    }
}
