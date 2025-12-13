using System.Windows;
using System.Collections.ObjectModel; // Still needed for ObservableCollection type reference in XAML binding
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Threading; // For Dispatcher
using OmniSync.Hub.Logic.Monitoring; // For HubMonitorService
using System; // For Environment.NewLine

namespace OmniSync.Hub.Presentation
{
    public partial class MainWindow : Window, INotifyPropertyChanged
    {
        public event PropertyChangedEventHandler? PropertyChanged; // Still implement for self if needed, but DataContext will notify

        private readonly HubMonitorService _hubMonitorService;

        public MainWindow(HubMonitorService hubMonitorService)
        {
            InitializeComponent();
            _hubMonitorService = hubMonitorService;
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
    }
}
