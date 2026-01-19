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
        private readonly ILogger<HubMonitorService> _logger;

        public event PropertyChangedEventHandler? PropertyChanged;

        // New events for UI updates
        public event EventHandler<string>? LogEntryAdded;
        public event EventHandler<string>? CommandUpdateOccurred;
        public event EventHandler<string>? ConnectionAdded; // New event
        public event EventHandler<string>? ConnectionRemoved; // New event
        public event EventHandler<string>? ExternalCommandReceived; // New event for tray notifications

        // Event handlers from RpcApiHub
        private EventHandler<string>? _anyCommandReceivedHandler;
        private EventHandler<string>? _clientConnectedHandler;
        private EventHandler<string>? _clientDisconnectedHandler;

        // Data to be exposed to UI
        public ObservableCollection<string> ActiveConnections { get; } = new ObservableCollection<string>();
        public ObservableCollection<string> LogMessages { get; } = new ObservableCollection<string>();
        
        private bool _isTftActive;
        public bool IsTftActive
        {
            get => _isTftActive;
            set
            {
                if (_isTftActive != value)
                {
                    _isTftActive = value;
                    OnPropertyChanged();
                    AddLogMessage($"TFT Focus State Changed: {value}");
                }
            }
        }

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

        private readonly string _instanceId = Guid.NewGuid().ToString().Substring(0, 8);
        private bool _isSubscribed = false;

        public HubMonitorService(
            IHostApplicationLifetime appLifetime,
            ILogger<HubMonitorService> logger)
        {
            _appLifetime = appLifetime;
            _logger = logger;

            // Define the event handler for RpcApiHub.AnyCommandReceived
            _anyCommandReceivedHandler = (sender, command) =>
            {
                LastIncomingCommand = command;
                CommandUpdateOccurred?.Invoke(this, command);
                
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
                AddLogMessage($"Client Disconnected: {connectionId}");
            };
            
            _logger.LogInformation($"HubMonitorService initialized (ID: {_instanceId}).");
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation($"HubMonitorService starting (ID: {_instanceId}).");
            
            if (!_isSubscribed)
            {
                _logger.LogInformation($"HubMonitorService (ID: {_instanceId}) subscribing to RpcApiHub events.");
                // Hook into RpcApiHub events
                RpcApiHub.AnyCommandReceived += _anyCommandReceivedHandler;
                RpcApiHub.ClientConnectedEvent += _clientConnectedHandler;
                RpcApiHub.ClientDisconnectedEvent += _clientDisconnectedHandler;
                _isSubscribed = true;
            }
            else
            {
                _logger.LogWarning($"HubMonitorService (ID: {_instanceId}) StartAsync called but already subscribed.");
            }

            LogEntryAdded?.Invoke(this, $"[{DateTime.Now:HH:mm:ss}] HubMonitorService started (ID: {_instanceId}).");
            return Task.CompletedTask;
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation($"HubMonitorService stopping (ID: {_instanceId}).");
            
            if (_isSubscribed)
            {
                _logger.LogInformation($"HubMonitorService (ID: {_instanceId}) unsubscribing from RpcApiHub events.");
                // Unsubscribe from events to prevent memory leaks
                if (_anyCommandReceivedHandler != null)
                {
                    RpcApiHub.AnyCommandReceived -= _anyCommandReceivedHandler;
                }
                if (_clientConnectedHandler != null) 
                {
                    RpcApiHub.ClientConnectedEvent -= _clientConnectedHandler;
                }
                if (_clientDisconnectedHandler != null) 
                {
                    RpcApiHub.ClientDisconnectedEvent -= _clientDisconnectedHandler;
                }
                _isSubscribed = false;
            }

            LogEntryAdded?.Invoke(this, $"[{DateTime.Now:HH:mm:ss}] HubMonitorService stopped (ID: {_instanceId}).");
            return Task.CompletedTask;
        }

        protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }

        public void AddLogMessage(string message)
        {
            var logEntry = $"[{DateTime.Now:HH:mm:ss}] (ID: {_instanceId}) {message}";
            
            // WPF Compatibility: Ensure ObservableCollection is updated on the UI thread if we are in a WPF context
            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    LogMessages.Insert(0, logEntry);
                });
            }
            else
            {
                LogMessages.Insert(0, logEntry);
            }

            LogEntryAdded?.Invoke(this, logEntry);
        }

        public void OnExternalCommandReceived(string command)
        {
            ExternalCommandReceived?.Invoke(this, command);
        }

        public void ClearLog()
        {
            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    LogMessages.Clear();
                });
            }
            else
            {
                LogMessages.Clear();
            }
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
