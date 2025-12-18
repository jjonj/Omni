using Microsoft.AspNetCore.SignalR;
using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Infrastructure.Services; // Added for FileService
using OmniSync.Hub.Models;
using OmniSync.Hub.Logic.Monitoring;
using Microsoft.Extensions.Logging; // Added for ILogger

namespace OmniSync.Hub.Presentation.Hubs
{
    public class RpcApiHub : Microsoft.AspNetCore.SignalR.Hub, IDisposable
    {
        // New: Event to notify of any command received by the Hub
        public static event EventHandler<string>? AnyCommandReceived;

        // New: Events for client connection/disconnection
        public static event EventHandler<string>? ClientConnectedEvent;
        public static event EventHandler<string>? ClientDisconnectedEvent;

        private readonly AuthService _authService;
        private readonly FileService _fileService;
        private readonly ClipboardService _clipboardService;
        private readonly CommandDispatcher _commandDispatcher;
        private readonly ProcessService _processService;
        private readonly HubEventSender _hubEventSender;
        private readonly InputService _inputService;
        private readonly AudioService _audioService;
        private readonly ShutdownService _shutdownService;
        private readonly RegistryService _registryService;
        private readonly HubMonitorService _hubMonitorService;
        private readonly ILogger<RpcApiHub> _logger; // Added for logging

        public RpcApiHub(AuthService authService, FileService fileService, ClipboardService clipboardService, CommandDispatcher commandDispatcher, ProcessService processService, HubEventSender hubEventSender, InputService inputService, AudioService audioService, ShutdownService shutdownService, RegistryService registryService, HubMonitorService hubMonitorService, ILogger<RpcApiHub> logger)
        {
            _authService = authService;
            _fileService = fileService;
            _clipboardService = clipboardService;
            _commandDispatcher = commandDispatcher;
            _processService = processService;
            _hubEventSender = hubEventSender;
            _inputService = inputService;
            _audioService = audioService;
            _shutdownService = shutdownService;
            _registryService = registryService;
            _hubMonitorService = hubMonitorService;
            _logger = logger;
        }

        public override async Task OnConnectedAsync()
        {
            _logger.LogInformation($"Client connected: {Context.ConnectionId}. Awaiting authentication.");
            ClientConnectedEvent?.Invoke(this, Context.ConnectionId);
            _hubEventSender.SubscribeForCommandOutput(Context.UserIdentifier ?? Context.ConnectionId, Context.ConnectionId);
            await base.OnConnectedAsync();
            
            // Send current modifier states to the newly connected client
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                await SendCurrentState();
            }
        }

        public override async Task OnDisconnectedAsync(Exception? exception)
        {
            _logger.LogInformation($"Client disconnected: {Context.ConnectionId}");
            ClientDisconnectedEvent?.Invoke(this, Context.ConnectionId);
            _hubEventSender.UnsubscribeFromCommandOutput(Context.UserIdentifier ?? Context.ConnectionId);
            await base.OnDisconnectedAsync(exception);
        }

        private async Task SendCurrentState()
        {
            await Clients.Caller.SendAsync("ModifierStateUpdated", "Shift", _inputService.IsShiftPressed);
            await Clients.Caller.SendAsync("ModifierStateUpdated", "Ctrl", _inputService.IsCtrlPressed);
            await Clients.Caller.SendAsync("ModifierStateUpdated", "Alt", _inputService.IsAltPressed);
            await Clients.Caller.SendAsync("ShutdownScheduled", _shutdownService.GetScheduledTime());
            await Clients.Caller.SendAsync("UpdateRunOnStartup", _registryService.IsRunOnStartupEnabled());
        }

        public bool Authenticate(string apiKey)
        {
            var isAuthenticated = _authService.Validate(apiKey);
            if (isAuthenticated)
            {
                Context.Items["IsAuthenticated"] = true;
                _logger.LogInformation($"Client authenticated: {Context.ConnectionId}");
                
                // Immediately send current states after successful authentication
                _ = SendCurrentState();
                
                return true;
            }

            _logger.LogWarning($"Client failed authentication: {Context.ConnectionId}");
            Context.Abort();
            return false;
        }

        public object GetHubStatus()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                return new
                {
                    ActiveConnections = _hubMonitorService.ActiveConnections,
                    LogMessages = _hubMonitorService.LogMessages,
                    LastIncomingCommand = _hubMonitorService.LastIncomingCommand,
                    IsRunOnStartupEnabled = _registryService.IsRunOnStartupEnabled(),
                    ScheduledShutdownTime = _shutdownService.GetScheduledTime()
                };
            }
            throw new UnauthorizedAccessException();
        }

        public void SetRunOnStartup(bool enable)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _registryService.SetRunOnStartup(enable);
                _hubMonitorService.AddLogMessage($"Run on startup set to {enable} via web/API.");
                // Broadcast update to all clients
                _ = Clients.All.SendAsync("UpdateRunOnStartup", enable);
            }
        }

        public float GetVolume()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "GetVolume");
                return _audioService.GetMasterVolume();
            }
            throw new UnauthorizedAccessException("Client is not authenticated.");
        }

        public bool IsMuted()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "IsMuted");
                return _audioService.IsMuted();
            }
            throw new UnauthorizedAccessException("Client is not authenticated.");
        }

        public void SendPayload(string command, JsonElement payload)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                // Invoke the event for SendPayload commands
                AnyCommandReceived?.Invoke(this, command);

                try
                {
                    _commandDispatcher.Dispatch(command, payload);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error dispatching command '{command}': {ex.Message}");
                }
            }
        }

        public void MouseMove(JsonElement payload)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "MouseMove");

                try
                {
                    // Get double values and round to nearest integer
                    int x = (int)Math.Round(payload.GetProperty("X").GetDouble());
                    int y = (int)Math.Round(payload.GetProperty("Y").GetDouble());
                    _inputService.MoveMouse(x, y);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error moving mouse: {ex.Message}");
                }
            }
        }        public void UpdateClipboard(string text)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                try
                {
                    _clipboardService.SetClipboardText(text);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error setting clipboard text: {ex.Message}");
                }
            }
        }


        public void PasteClipboard(string text)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "PasteClipboard");

                try
                {
                    _clipboardService.SetClipboardText(text);
                    // Simulate Ctrl+V
                    _inputService.KeyDown((ushort)0x11); // VK_CONTROL
                    _inputService.SendKeyPress((ushort)0x56); // VK_V
                    _inputService.KeyUp((ushort)0x11); // VK_CONTROL
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error pasting clipboard text: {ex.Message}");
                }
            }
        }

        public async Task ExecuteCommand(string command)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }

                // Invoke the event for ExecuteCommand commands
                AnyCommandReceived?.Invoke(this, command);

                (string commandName, List<string> args) = ParseCommand(command);

                switch (commandName.ToLowerInvariant())
                {
                    case "write_file":
                        if (args.Count == 2)
                        {
                            // Special handling for write_file if needed
                            _fileService.WriteFile(args[0], args[1]);
                            await Clients.Caller.SendAsync("ReceiveCommandOutput", $"File '{args[0]}' written successfully.");
                        }
                        else
                        {
                            await Clients.Caller.SendAsync("ReceiveCommandOutput", "Usage: write_file \"filepath\" \"content\"");
                        }
                        break;
                    case "list_notes":
                        var files = Directory.GetFiles(_fileService.GetNoteRootPath(), "*.md", SearchOption.TopDirectoryOnly);
                        var fileNames = new List<string>();
                        foreach (var file in files)
                        {
                            fileNames.Add(Path.GetFileName(file));
                        }
                        await Clients.Caller.SendAsync("ReceiveCommandOutput", string.Join("\n", fileNames));
                        break;
                    // Add other commands here
                    default:
                        // Fallback to process service for unrecognized commands
                        await _processService.ExecuteCommand(command);
                        await Clients.Caller.SendAsync("CommandExecutionCompleted", command);
                        break;
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error executing command {command}: {ex.Message}");
                await Clients.Caller.SendAsync("ReceiveCommandOutput", $"Error: {ex.Message}");
            }
        }

        public IEnumerable<ProcessInfo> ListProcesses()
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }
                return _processService.ListProcesses();
            }
            catch (Exception ex)
            {
                    Console.WriteLine($"Error listing processes: {ex.Message}");
                return new List<ProcessInfo>();
            }
        }

        public bool KillProcess(int processId)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }
                return _processService.KillProcess(processId);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error killing process {processId}: {ex.Message}");
                return false;
            }
        }

        public string GetNoteContent(string filename)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }

                return _fileService.ReadFile(filename);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error getting note content for '{filename}': {ex.Message}");
                return $"Error: Could not retrieve content for '{filename}'.";
            }
        }

        public async Task GetAvailableDrives()
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    await Clients.Caller.SendAsync("ReceiveError", "Unauthorized: Please authenticate first.");
                    return;
                }

                AnyCommandReceived?.Invoke(this, "GetAvailableDrives");
                
                var drives = _fileService.GetDrives();
                
                // Python script expects "ReceiveAvailableDrives" event
                await Clients.Caller.SendAsync("ReceiveAvailableDrives", drives);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error getting drives: {ex.Message}");
                await Clients.Caller.SendAsync("ReceiveError", $"Error: {ex.Message}");
            }
        }

        public async Task<IEnumerable<FileSystemEntry>> ListDirectory(string path)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new HubException("Unauthorized");
                }

                AnyCommandReceived?.Invoke(this, $"ListDirectory: {path}");

                var contents = _fileService.ListDirectoryContents(path);

                // Python script expects "ReceiveDirectoryContents" event
                await Clients.All.SendAsync("ReceiveDirectoryContents", contents);
                
                return contents;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error listing directory '{path}'");
                throw new HubException($"Error listing directory: {ex.Message}");
            }
        }

        public async Task<IEnumerable<FileSystemEntry>> SearchFiles(string path, string query)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new HubException("Unauthorized");
                }

                AnyCommandReceived?.Invoke(this, $"SearchFiles in {path}: {query}");

                var contents = _fileService.SearchFiles(path, query);

                // Reuse ReceiveDirectoryContents for search results
                await Clients.All.SendAsync("ReceiveDirectoryContents", contents);
                
                return contents;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error searching directory '{path}'");
                throw new HubException($"Error searching: {ex.Message}");
            }
        }

        public async Task<byte[]> GetFileChunk(string filePath, long offset, int chunkSize)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }

                // Invoke the event for GetFileChunk commands
                AnyCommandReceived?.Invoke(this, $"GetFileChunk: {filePath} Offset: {offset} Size: {chunkSize}");

                return _fileService.GetFileChunk(filePath, offset, chunkSize);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error getting file chunk for '{filePath}': {ex.Message}");
                throw new HubException($"Error getting file chunk: {ex.Message}", ex);
            }
        }

        public async Task SendBrowserCommand(string command, string url, bool newTab)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"Browser: {command} -> {url}");
                
                // Broadcast to all clients (The Chrome extension will pick this up)
                await Clients.All.SendAsync("ReceiveBrowserCommand", command, url, newTab);
            }
        }

        public async Task SendCleanupPatterns(List<string> patterns)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"SendCleanupPatterns: {patterns.Count} patterns");
                
                // Forward to all clients (Android will pick this up)
                await Clients.All.SendAsync("ReceiveCleanupPatterns", patterns);
            }
        }

        public async Task AddCleanupPattern(string pattern)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"AddCleanupPattern: {pattern}");
                
                // Forward to all clients (Chrome extension will pick this up)
                await Clients.All.SendAsync("ReceiveBrowserCommand", "AddCleanupPattern", pattern, false);
            }
        }

        public async Task SendTabInfo(string title, string url)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"SendTabInfo: {title} -> {url}");
                await Clients.All.SendAsync("ReceiveTabInfo", title, url);
            }
        }

        public async Task SendTabList(List<JsonElement> tabs)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"SendTabList: {tabs.Count} tabs");
                await Clients.All.SendAsync("ReceiveTabList", tabs);
            }
        }

        public async Task SendTabToPhone(string url)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"SendTabToPhone: {url}");
                await Clients.All.SendAsync("ReceiveTabToPhone", url);
            }
        }

        public async Task SendAiMessage(string message)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                string preview = message.Length > 20 ? message.Substring(0, 20) + "..." : message;
                AnyCommandReceived?.Invoke(this, $"AI Message Sent: {preview}");
                // Broadcast the user message so other clients (like a CLI listener) can see it
                await Clients.All.SendAsync("ReceiveAiMessage", Context.ConnectionId, message);
            }
        }

        public async Task SendAiResponse(string response)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "AI Response Received");
                await Clients.All.SendAsync("ReceiveAiResponse", response);
            }
        }

        public async Task NotifyCortexActivity(string activityName, string activityType)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"Cortex Activity: {activityName}");
                await Clients.All.SendAsync("ReceiveCortexActivity", activityName, activityType);
            }
        }

        private (string commandName, List<string> args) ParseCommand(string commandString)
        {
            var parts = new List<string>();
            var inQuote = false;
            var currentPart = new System.Text.StringBuilder();

            for (int i = 0; i < commandString.Length; i++)
            {
                if (commandString[i] == '"')
                {
                    inQuote = !inQuote;
                    // If we just closed a quote, and the current part has content, add it.
                    // This handles cases like `command "arg1" "arg2"`
                    if (!inQuote && currentPart.Length > 0)
                    {
                        parts.Add(currentPart.ToString());
                        currentPart.Clear();
                    }
                    else if (inQuote && currentPart.Length > 0)
                    {
                        // If we just opened a quote, and there's content, that means it's a command name followed by a space then a quote
                        parts.Add(currentPart.ToString());
                        currentPart.Clear();
                    }
                }
                else if (commandString[i] == ' ' && !inQuote)
                {
                    if (currentPart.Length > 0)
                    {
                        parts.Add(currentPart.ToString());
                        currentPart.Clear();
                    }
                }
                else
                {
                    currentPart.Append(commandString[i]);
                }
            }

            if (currentPart.Length > 0)
            {
                parts.Add(currentPart.ToString());
            }

            if (parts.Count == 0)
            {
                return (string.Empty, new List<string>());
            }

            string commandName = parts[0];
            List<string> args = parts.Count > 1 ? parts.GetRange(1, parts.Count - 1) : new List<string>();

            return (commandName, args);
        }
    }
}

