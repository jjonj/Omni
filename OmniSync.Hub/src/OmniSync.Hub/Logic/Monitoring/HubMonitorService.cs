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
                LastIncomingCommand = command;
                CommandUpdateOccurred?.Invoke(this, command);
                _ = _hubEventSender.BroadcastCommandUpdate(command);
                
                // Filter out verbose commands from the persistent log
                if (command == "MouseMove" || command.Contains("GetVolume") || command.Contains("GetFileChunk"))
                {
                    return;
                }

                AddLogMessage($"Command Received: {command}");
            };

            // Define the event handler for RpcApiHub.ClientConnectedEvent
            _clientConnectedHandler = (sender, connectionId) =>
            {
                SafeUpdateConnections(() =>
                {
                    if (!ActiveConnections.Contains(connectionId))
                    {
                        ActiveConnections.Add(connectionId);
                    }
                });
                ConnectionAdded?.Invoke(this, connectionId); // Now raises event
                _ = _hubEventSender.BroadcastConnectionAdded(connectionId);
                AddLogMessage($"Client Connected: {connectionId}");
            };

            // Define the event handler for RpcApiHub.ClientDisconnectedEvent
            _clientDisconnectedHandler = (sender, connectionId) =>
            {
                SafeUpdateConnections(() =>
                {
                    ActiveConnections.Remove(connectionId);
                });
                ConnectionRemoved?.Invoke(this, connectionId); // Now raises event
                _ = _hubEventSender.BroadcastConnectionRemoved(connectionId);
                AddLogMessage($"Client Disconnected: {connectionId}");
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
            var logEntry = $"[{DateTime.Now:HH:mm:ss}] {message}";
            
            // WPF Compatibility: Ensure ObservableCollection is updated on the UI thread if we are in a WPF context
            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    LogMessages.Add(logEntry);
                });
            }
            else
            {
                LogMessages.Add(logEntry);
            }

            LogEntryAdded?.Invoke(this, logEntry);
            
            // Broadcast to SignalR clients (e.g. Web Monitor)
            _ = _hubEventSender.BroadcastLogEntryAdded(logEntry);
        }

        private void SafeUpdateConnections(Action action)
        {
            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.BeginInvoke(action);
            }
            else
            {
                action();
            }
        }
    }
}
