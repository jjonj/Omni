using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Presentation.Hubs;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services
{
    public class PayloadEnvelope
    {
        public string Target { get; set; } = string.Empty;
        public string Command { get; set; } = string.Empty;
        public object Payload { get; set; } = new object();
        public long Timestamp { get; set; }
    }

    public class HubEventSender
    {
        private readonly ILogger<HubEventSender> _logger;
        private readonly IHubContext<RpcApiHub> _hubContext;
        private readonly ProcessService _processService;
        private readonly InputService _inputService;
        private readonly AudioService _audioService;
        private readonly ShutdownService _shutdownService;
        private readonly CommandDispatcher _commandDispatcher;
        private readonly FileService _fileService; // Added FileService dependency
        private readonly AiCliService _aiCliService; // Added AiCliService
        private readonly HubSettingsService _settingsService;
        private readonly HubMonitorService _monitorService;
        private string? _lastPlayedDialogPrompt;
        private DateTime _lastPlayedDialogTime = DateTime.MinValue;
        private readonly Dictionary<string, string> _clientCommandOutputSubscriptions = new Dictionary<string, string>(); // ClientId -> ConnectionId for command output

        public HubEventSender(ILogger<HubEventSender> logger, IHubContext<RpcApiHub> hubContext, ProcessService processService, InputService inputService, AudioService audioService, ShutdownService shutdownService, CommandDispatcher commandDispatcher, FileService fileService, AiCliService aiCliService, HubSettingsService settingsService, HubMonitorService monitorService) // Added AiCliService
        {
            _logger = logger;
            _hubContext = hubContext;
            _processService = processService;
            _inputService = inputService;
            _audioService = audioService;
            _shutdownService = shutdownService;
            _commandDispatcher = commandDispatcher;
            _fileService = fileService; // Assign FileService
            _aiCliService = aiCliService;
            _settingsService = settingsService;
            _monitorService = monitorService;

            _processService.CommandOutputReceived += OnCommandOutputReceived;
            _inputService.ModifierStateChanged += OnModifierStateChanged;
            _shutdownService.ShutdownScheduled += OnShutdownScheduled;
            _shutdownService.ModeChanged += OnShutdownModeChanged;
            _commandDispatcher.AddCleanupPatternRequested += OnAddCleanupPatternRequested;
            _commandDispatcher.ExternalCommandDispatched += (s, e) => {
                if (e.Command.StartsWith("TFT_"))
                {
                    _ = _hubContext.Clients.All.SendAsync("ReceiveTftCommand", e.Command, e.Payload);
                }
                else if (e.Command == "RELOAD_CHROME_EXTENSION")
                {
                    _monitorService.AddLogMessage("[Chrome] Reloading extension via hotkey.");
                    _ = _hubContext.Clients.All.SendAsync("ReceiveBrowserCommand", "ReloadExtension", "", false);
                }
            };
            // Subscribe to FileService events
            _fileService.FileWritten += OnFileWritten;
            _fileService.BrowseFileWritten += OnBrowseFileWritten;
            _fileService.FileChanged += OnFileSystemChanged;
            
            _aiCliService.ResponseReceived += OnAiCliResponseReceived;
            _aiCliService.DialogReceived += OnAiCliDialogReceived;

            // Subscribe to Monitor events
            _monitorService.LogEntryAdded += (s, msg) => _ = BroadcastLogEntryAdded(msg);
            _monitorService.CommandUpdateOccurred += (s, cmd) => _ = BroadcastCommandUpdate(cmd);
            _monitorService.ConnectionAdded += (s, id) => _ = BroadcastConnectionAdded(id);
            _monitorService.ConnectionRemoved += (s, id) => _ = BroadcastConnectionRemoved(id);
        }

        private async void OnAiCliDialogReceived(object? sender, GeminiDialogEventArgs e)
        {
            _logger.LogInformation($"[HubEventSender] Received Dialog from PID {e.Pid}. Type: {e.Type}, Prompt: {e.Prompt}");
            _monitorService.AddLogMessage($"AI Dialog ({e.Type}): {e.Prompt}");

            // Play sound on Hub ONLY if it's a new prompt AND cooldown has passed (30s)
            if (_lastPlayedDialogPrompt != e.Prompt || (DateTime.Now - _lastPlayedDialogTime).TotalSeconds > 30)
            {
                _audioService.PlayBlip();
                _lastPlayedDialogPrompt = e.Prompt;
                _lastPlayedDialogTime = DateTime.Now;
            }

            // Special handling for pro_quota
            if (e.Type == "pro_quota")
            {
                await Task.Delay(5000);
                if (e.Prompt.Contains("flash", StringComparison.OrdinalIgnoreCase))
                {
                    _logger.LogInformation($"[HubEventSender] Handling pro_quota dialog for PID {e.Pid} (Flash Model). Auto-retrying later...");
                    _monitorService.AddLogMessage("Quota reached (Flash). Auto-selecting 'retry_later' in 5s...");
                    await _aiCliService.SendDialogResponseAsync("retry_later", e.Pid);
                }
                else
                {
                    _logger.LogInformation($"[HubEventSender] Handling pro_quota dialog for PID {e.Pid} (Standard). Auto-retrying always...");
                    _monitorService.AddLogMessage("Quota reached. Auto-selecting 'retry_always' in 5s...");
                    await _aiCliService.SendDialogResponseAsync("retry_always", e.Pid);
                }
                return;
            }

            // Auto-approve based on settings
            if (_settingsService.Settings.AutoApprovePatterns != null)
            {
                foreach (var pattern in _settingsService.Settings.AutoApprovePatterns)
                {
                    if (e.Prompt.Contains(pattern, StringComparison.OrdinalIgnoreCase))
                    {
                        _logger.LogInformation($"[HubEventSender] Auto-approving '{pattern}' for PID {e.Pid}");
                        _monitorService.AddLogMessage($"Auto-approving dialog pattern: {pattern}");
                        await _aiCliService.SendDialogResponseAsync("yes", e.Pid);
                        return;
                    }
                }
            }

            await _hubContext.Clients.All.SendAsync("ReceiveAiDialog", e.Pid, e.Type, e.Prompt, e.Options);
        }

        private async void OnAiCliResponseReceived(object? sender, GeminiResponseEventArgs e)
        {
            int broadcastPid = e.Pid;
            if (broadcastPid <= 0)
            {
                broadcastPid = _aiCliService.GetTargetPid();
                if (broadcastPid <= 0) broadcastPid = -1;
                _logger.LogWarning($"[HubEventSender] AI response received with invalid PID {e.Pid}. Falling back to target PID {broadcastPid}. Text: {e.Text.Take(20)}...");
            }

            if (e.IsHistory)
            {
                _logger.LogInformation($"[HubEventSender] Broadcasting History for PID {broadcastPid}. JSON length: {e.Text.Length}");
                _monitorService.AddLogMessage($"[AI] History received for PID {broadcastPid} ({e.Text.Length} bytes)");
                await _hubContext.Clients.All.SendAsync("ReceiveAiHistory", e.Text, broadcastPid);
            }
            else if (e.IsCodeDiff)
            {
                _logger.LogInformation($"[HubEventSender] Broadcasting Code Diff for PID {broadcastPid}");
                // _monitorService.AddLogMessage($"[AI] Code diff received for PID {broadcastPid}");
                await _hubContext.Clients.All.SendAsync("ReceiveAiCodeDiff", e.Text, broadcastPid);
            }
            else if (e.Text.StartsWith("Thinking: "))
            {
                string thought = e.Text.Substring("Thinking: ".Length);
                // _logger.LogInformation($"[HubEventSender] Broadcasting Thought for PID {broadcastPid}: {thought.Substring(0, Math.Min(thought.Length, 50))}...");
                // _monitorService.AddLogMessage($"[AI] Thought from PID {broadcastPid}: {thought.Take(30)}...");
                
                await _hubContext.Clients.All.SendAsync("ReceiveAiThought", thought, broadcastPid);
            }
            else 
            {
                 // Normal response received, clear the last dialog prompt tracker
                 // so the next dialog (even if identical) will play its sound.
                 _lastPlayedDialogPrompt = null;

                 // Always send the text if present
                 if (!string.IsNullOrEmpty(e.Text))
                 {
                     if (e.IsUser)
                     {
                         // Broadcast as a user message from CLI
                         await _hubContext.Clients.All.SendAsync("ReceiveAiMessage", "CLI_USER", e.Text, broadcastPid);
                     }
                     else
                     {
                         string preview = e.Text.Length > 50 ? e.Text.Substring(0, 50) + "..." : e.Text;
                         // Console.WriteLine($"[HubEventSender] Broadcasting Response for PID {broadcastPid}: {preview}");
                         await _hubContext.Clients.All.SendAsync("ReceiveAiResponse", e.Text, broadcastPid);
                     }
                 }

                 if (e.IsFinished)
                 {
                     // Console.WriteLine($"[HubEventSender] Turn FINISHED for PID {broadcastPid}");
                     await _hubContext.Clients.All.SendAsync("ReceiveAiStatus", "FINISHED", broadcastPid);
                 }
            }
        }

        private async void OnShutdownModeChanged(object? sender, ShutdownMode mode)
        {
            await _hubContext.Clients.All.SendAsync("ShutdownModeUpdated", mode.ToString());
        }

        private async void OnAddCleanupPatternRequested(object? sender, string pattern)
        {
            await _hubContext.Clients.All.SendAsync("ReceiveBrowserCommand", "AddCleanupPattern", pattern, false);
        }

        private async void OnModifierStateChanged(object? sender, ModifierStateEventArgs e)
        {
            await _hubContext.Clients.All.SendAsync("ModifierStateUpdated", e.Modifier.ToString(), e.IsPressed);
        }

        private async void OnShutdownScheduled(object? sender, DateTime? scheduledTime)
        {
            await _hubContext.Clients.All.SendAsync("ShutdownScheduled", scheduledTime);
        }

        public async Task BroadcastSessions()
        {
            var sessions = _aiCliService.GetActiveSessions();
            await _hubContext.Clients.All.SendAsync("ReceiveAiSessions", sessions);
        }

        public async Task BroadcastLogEntryAdded(string message)
        {
            await _hubContext.Clients.All.SendAsync("LogEntryAdded", message);
        }

        public async Task BroadcastCommandUpdate(string command)
        {
            await _hubContext.Clients.All.SendAsync("CommandUpdateOccurred", command);
        }

        public async Task BroadcastConnectionAdded(string connectionId)
        {
            await _hubContext.Clients.All.SendAsync("ConnectionAdded", connectionId);
        }

        public async Task BroadcastConnectionRemoved(string connectionId)
        {
            await _hubContext.Clients.All.SendAsync("ConnectionRemoved", connectionId);
        }

        public async Task SendPayloadToAndroid(string command, object payload)
        {
            await BroadcastLogEntryAdded($"Broadcasting '{command}' to Android...");
            await _hubContext.Clients.All.SendAsync("ReceivePayload", new PayloadEnvelope
            {
                Target = "Android",
                Command = command,
                Payload = payload,
                Timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
            });
        }

        public async Task SendAiError(string connectionId, string errorMessage, int targetPid = -1)
        {
            int broadcastPid = targetPid > 0 ? targetPid : _aiCliService.GetTargetPid();
            await _hubContext.Clients.Client(connectionId).SendAsync("ReceiveAiResponse", errorMessage, broadcastPid);
        }

        // Method to be called by RpcApiHub when a client connects and wants command output
        public void SubscribeForCommandOutput(string clientId, string connectionId)
        {
            _clientCommandOutputSubscriptions[clientId] = connectionId;
        }

        public void UnsubscribeFromCommandOutput(string clientId)
        {
            _clientCommandOutputSubscriptions.Remove(clientId);
        }

        private async void OnFileWritten(object? sender, string filePath)
        {
            await BroadcastLogEntryAdded($"File '{filePath}' synced to PC.");
        }

        private async void OnBrowseFileWritten(object? sender, string filePath)
        {
            await BroadcastLogEntryAdded($"Browse file '{filePath}' synced to PC.");
        }

        private async void OnFileSystemChanged(object? sender, string fullPath)
        {
            try
            {
                await _hubContext.Clients.All.SendAsync("FileChanged", fullPath, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Failed to broadcast FileChanged for {fullPath}: {ex.Message}");
            }
        }

        private async void OnCommandOutputReceived(object sender, string output)
        {
            // Iterate over all subscribed clients and send the output
            foreach (var connectionId in _clientCommandOutputSubscriptions.Values.ToList()) // Use ToList() to avoid modification during iteration
            {
                try
                {
                    await _hubContext.Clients.Client(connectionId).SendAsync("ReceiveCommandOutput", output);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error sending command output to client {connectionId}: {ex.Message}");
                    // Optionally, remove disconnected clients from the subscription list here.
                }
            }
        }
    }
}
