using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Presentation.Hubs; // New: For RpcApiHub.AnyCommandReceived
using System;
using System.Collections.Concurrent;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;

namespace OmniSync.Hub.Logic.Monitoring
{
    public class HubMonitorService : IHostedService, INotifyPropertyChanged
    {
        private readonly IHostApplicationLifetime _appLifetime;
        private readonly HubEventSender _hubEventSender;
        private readonly ILogger<HubMonitorService> _logger;

        public event PropertyChangedEventHandler? PropertyChanged;

        // New events for UI updates
        public event EventHandler<string>? LogEntryAdded;
        public event EventHandler<string>? CommandUpdateOccurred;
        public event EventHandler<string>? ConnectionAdded; // New event
        public event EventHandler<string>? ConnectionRemoved; // New event

        // Event handlers from RpcApiHub
        private EventHandler<string>? _anyCommandReceivedHandler;
        private EventHandler<string>? _clientConnectedHandler;
        private EventHandler<string>? _clientDisconnectedHandler;

        // Data to be exposed to UI
        public ObservableCollection<string> ActiveConnections { get; } = new ObservableCollection<string>();
        public ObservableCollection<string> LogMessages { get; } = new ObservableCollection<string>();
        private string _lastIncomingCommand = "None";
        public string LastIncomingCommand
        {
            get => _lastIncomingCommand;
            private set
            {
                if (_lastIncomingCommand != value)
                {
                    _lastIncomingCommand = value;
                    OnPropertyChanged();
                }
            }
        }

        public HubMonitorService(
            IHostApplicationLifetime appLifetime,
            HubEventSender hubEventSender,
            ILogger<HubMonitorService> logger)
        {
            _appLifetime = appLifetime;
            _hubEventSender = hubEventSender;
            _logger = logger;

            // Define the event handler for RpcApiHub.AnyCommandReceived
            _anyCommandReceivedHandler = (sender, command) =>
            {
                CommandUpdateOccurred?.Invoke(this, command);
                
                // Filter out verbose commands from the persistent log
                if (command == "MouseMove" || command.Contains("GetVolume") || command.Contains("GetFileChunk"))
                {
                    return;
                }

                LogEntryAdded?.Invoke(this, $"[{DateTime.Now:HH:mm:ss}] Command Received: {command}");
            };

            // Define the event handler for RpcApiHub.ClientConnectedEvent
            _clientConnectedHandler = (sender, connectionId) =>
            {
                ConnectionAdded?.Invoke(this, connectionId); // Now raises event
                LogEntryAdded?.Invoke(this, $"[{DateTime.Now:HH:mm:ss}] Client Connected: {connectionId}");
            };

            // Define the event handler for RpcApiHub.ClientDisconnectedEvent
            _clientDisconnectedHandler = (sender, connectionId) =>
            {
                ConnectionRemoved?.Invoke(this, connectionId); // Now raises event
                LogEntryAdded?.Invoke(this, $"[{DateTime.Now:HH:mm:ss}] Client Disconnected: {connectionId}");
            };

            // Hook into RpcApiHub events
            RpcApiHub.AnyCommandReceived += _anyCommandReceivedHandler;
            RpcApiHub.ClientConnectedEvent += _clientConnectedHandler; // New
            RpcApiHub.ClientDisconnectedEvent += _clientDisconnectedHandler; // New
            
            _logger.LogInformation("HubMonitorService initialized.");
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation("HubMonitorService starting.");
            LogEntryAdded?.Invoke(this, $"[{DateTime.Now:HH:mm:ss}] HubMonitorService started.");
            return Task.CompletedTask;
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation("HubMonitorService stopping.");
            // Unsubscribe from events to prevent memory leaks
            if (_anyCommandReceivedHandler != null)
            {
                RpcApiHub.AnyCommandReceived -= _anyCommandReceivedHandler;
            }
            if (_clientConnectedHandler != null) // New
            {
                RpcApiHub.ClientConnectedEvent -= _clientConnectedHandler;
            }
            if (_clientDisconnectedHandler != null) // New
            {
                RpcApiHub.ClientDisconnectedEvent -= _clientDisconnectedHandler;
            }
            LogEntryAdded?.Invoke(this, $"[{DateTime.Now:HH:mm:ss}] HubMonitorService stopped.");
            return Task.CompletedTask;
        }

        protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }

        public void AddLogMessage(string message)
        {
            // This ensures thread safety for ObservableCollection when updated from different threads
            // For UI updates, it's best to Invoke on the Dispatcher, which the MainWindow does.
            // For now, we'll just add to the collection and invoke the event.
            LogMessages.Add($"[{DateTime.Now:HH:mm:ss}] {message}");
            LogEntryAdded?.Invoke(this, $"[{DateTime.Now:HH:mm:ss}] {message}");
        }
    }
}
