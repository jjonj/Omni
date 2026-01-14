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
        private readonly AiCliService _aiCliService;
        private readonly PcgPersistentService _pcgService;
        private readonly HubSettingsService _settingsService;
        private readonly ILogger<RpcApiHub> _logger; // Added for logging

        public RpcApiHub(AuthService authService, FileService fileService, ClipboardService clipboardService, CommandDispatcher commandDispatcher, ProcessService processService, HubEventSender hubEventSender, InputService inputService, AudioService audioService, ShutdownService shutdownService, RegistryService registryService, HubMonitorService hubMonitorService, AiCliService aiCliService, PcgPersistentService pcgService, HubSettingsService settingsService, ILogger<RpcApiHub> logger)
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
            _aiCliService = aiCliService;
            _pcgService = pcgService;
            _settingsService = settingsService;
            _logger = logger;
        }

        public override async Task OnConnectedAsync()
        {
            var ip = Context.GetHttpContext()?.Connection.RemoteIpAddress?.ToString();
            _logger.LogInformation($"Client connected: {Context.ConnectionId} from IP: {ip}. Awaiting authentication.");
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
            await Clients.Caller.SendAsync("ModifierStateUpdated", "Win", _inputService.IsWinPressed);
            await Clients.Caller.SendAsync("ShutdownScheduled", _shutdownService.GetScheduledTime());
            await Clients.Caller.SendAsync("ShutdownModeUpdated", _shutdownService.GetCurrentMode().ToString());
            await Clients.Caller.SendAsync("UpdateRunOnStartup", _registryService.IsRunOnStartupEnabled());
        }

        public void ToggleShutdownMode()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var currentMode = _shutdownService.GetCurrentMode();
                var newMode = currentMode == ShutdownMode.Shutdown 
                    ? ShutdownMode.Sleep 
                    : ShutdownMode.Shutdown;
                
                _shutdownService.SetMode(newMode);
                _hubMonitorService.AddLogMessage($"Shutdown mode toggled to: {newMode} via remote.");
                AnyCommandReceived?.Invoke(this, $"ToggleShutdownMode: {newMode}");
            }
        }

        public bool Authenticate(string apiKey)
        {
            var ip = Context.GetHttpContext()?.Connection.RemoteIpAddress?.ToString();
            var isAuthenticated = _authService.Validate(apiKey);
            if (isAuthenticated)
            {
                Context.Items["IsAuthenticated"] = true;
                _logger.LogInformation($"Client authenticated: {Context.ConnectionId} from IP: {ip}");
                
                // Immediately send current states after successful authentication
                _ = SendCurrentState();
                
                return true;
            }

            _logger.LogWarning($"Client failed authentication: {Context.ConnectionId} from IP: {ip}");
            Context.Abort();
            return false;
        }

        public HubSettings GetSettings()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var settingsService = Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService;
                return settingsService?.Settings ?? new HubSettings();
            }
            throw new UnauthorizedAccessException();
        }

        public void UpdateHotkeys(List<HotkeyConfig> hotkeys)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var settingsService = Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService;
                if (settingsService != null)
                {
                    settingsService.Settings.Hotkeys = hotkeys;
                    settingsService.SaveSettings();
                    _hubMonitorService.AddLogMessage("Hotkeys updated via web interface.");
                }
            }
            else
            {
                throw new UnauthorizedAccessException();
            }
        }

        public void UpdateProjects(List<Project> projects)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var settingsService = Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService;
                if (settingsService != null)
                {
                    settingsService.Settings.Projects = projects;
                    settingsService.SaveSettings();
                    _hubMonitorService.AddLogMessage("Projects updated via web interface.");
                }
            }
            else
            {
                throw new UnauthorizedAccessException();
            }
        }

        public void AddProject(Project project)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var settingsService = Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService;
                settingsService?.AddProject(project);
            }
        }

        public void UpdateProject(Project project)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var settingsService = Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService;
                settingsService?.UpdateProject(project);
            }
        }

        public void RemoveProject(Guid id)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var settingsService = Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService;
                settingsService?.RemoveProject(id);
            }
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

        public void UpdateTellPcSettings(string workspace, string systemContext, bool soundEnabled)
        {
            try
            {
                if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
                {
                    _settingsService.UpdateTellPcSettings(workspace, systemContext, soundEnabled);
                    _hubMonitorService.AddLogMessage("Tell PC settings updated via web interface.");
                }
                else
                {
                    throw new HubException("Not authenticated.");
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error updating Tell PC settings.");
                throw new HubException($"Server error: {ex.Message}");
            }
        }

        public void AddMapping(string key, string path)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _settingsService.AddMapping(key, path);
                _hubMonitorService.AddLogMessage($"Exe mapping added: {key} -> {path}");
            }
        }

        public void RemoveMapping(string key)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _settingsService.RemoveMapping(key);
                _hubMonitorService.AddLogMessage($"Exe mapping removed: {key}");
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

        public List<string> GetHubLog()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "GetHubLog");
                return new List<string>(_hubMonitorService.LogMessages);
            }
            throw new UnauthorizedAccessException();
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
        }        public string GetClipboardText()
        {
            try
            {
                return _clipboardService.GetClipboardText();
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error getting clipboard text via RPC");
                return string.Empty;
            }
        }

        public void UpdateClipboard(string text)
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

        public async Task<IEnumerable<FileSystemEntry>> GetAvailableDrives()
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    await Clients.Caller.SendAsync("ReceiveError", "Unauthorized: Please authenticate first.");
                    return new List<FileSystemEntry>();
                }

                AnyCommandReceived?.Invoke(this, "GetAvailableDrives");
                
                var drives = _fileService.GetDrives();
                
                // Python script expects "ReceiveAvailableDrives" event
                await Clients.Caller.SendAsync("ReceiveAvailableDrives", drives);

                return drives;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error getting drives: {ex.Message}");
                await Clients.Caller.SendAsync("ReceiveError", $"Error: {ex.Message}");
                return new List<FileSystemEntry>();
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
                // Ensure watcher for target path happens in the service; no extra logic needed here

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

        public IEnumerable<FileSystemEntry> SearchFiles(string path, string query)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"SearchFiles: {path} (Query: {query})");
                return _fileService.SearchFiles(path, query);
            }
            return new List<FileSystemEntry>();
        }

        public FileSystemEntry? GetFileInfo(string path)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"GetFileInfo: {path}");
                try
                {
                    return _fileService.GetFileInfo(path);
                }
                catch
                {
                    return null;
                }
            }
            return null;
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

        public bool WriteFileContent(string filePath, string content)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"WriteFileContent: {filePath}");
                try
                {
                    return _fileService.WriteBrowseFile(filePath, content);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, $"Error writing file content for '{filePath}'");
                    throw new HubException($"Error writing file: {ex.Message}", ex);
                }
            }
            else
            {
                throw new UnauthorizedAccessException("Client is not authenticated.");
            }
        }

        public bool DeleteFile(string filePath)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"DeleteFile: {filePath}");
                try
                {
                    return _fileService.DeleteEntry(filePath);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, $"Error deleting file '{filePath}'");
                    throw new HubException($"Error deleting file: {ex.Message}", ex);
                }
            }
            else
            {
                throw new UnauthorizedAccessException("Client is not authenticated.");
            }
        }

        public bool CopyFile(string source, string dest)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"CopyFile: {source} -> {dest}");
                try
                {
                    return _fileService.CopyEntry(source, dest);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, $"Error copying file from '{source}' to '{dest}'");
                    throw new HubException($"Error copying file: {ex.Message}", ex);
                }
            }
            else
            {
                throw new UnauthorizedAccessException("Client is not authenticated.");
            }
        }

        public bool MoveFile(string source, string dest)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"MoveFile: {source} -> {dest}");
                try
                {
                    return _fileService.MoveEntry(source, dest);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, $"Error moving file from '{source}' to '{dest}'");
                    throw new HubException($"Error moving file: {ex.Message}", ex);
                }
            }
            else
            {
                throw new UnauthorizedAccessException("Client is not authenticated.");
            }
        }

        public async Task SendBrowserCommand(string command, string url, bool newTab)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"Browser: {command} -> {url}");
                
                // Logic to ensure browser is running if no extension is connected
                // Note: We don't have a direct way to check 'extension connected' easily here without tracking connection types,
                // but we can check if vivaldi is running at all as a heuristic.
                if (!_processService.IsProcessRunning("vivaldi"))
                {
                    _logger.LogInformation("[RpcApiHub] Vivaldi not running. Attempting to launch via Win+2.");
                    _hubMonitorService.AddLogMessage("Vivaldi not running. Triggering Win+2 launch.");
                    
                    const ushort VK_LWIN = 0x5B;
                    const ushort VK_2 = 0x32;
                    
                    _inputService.KeyDown(VK_LWIN);
                    _inputService.SendKeyPress(VK_2);
                    _inputService.KeyUp(VK_LWIN);
                    
                    // Give it some time to start before sending the command
                    await Task.Delay(2000);
                }

                // Broadcast to all clients (The Chrome extension will pick this up)
                await Clients.All.SendAsync("ReceiveBrowserCommand", command, url, newTab);
            }
        }

        public async Task CloseSpecificTab(int tabId)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"CloseSpecificTab: {tabId}");
                await Clients.All.SendAsync("ReceiveBrowserCommand", "CloseTab", tabId.ToString(), false);
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

        public async Task SendAiMessage(string message, int? sessionId = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                string preview = message.Length > 100 ? message.Substring(0, 100) + "..." : message;
                _logger.LogInformation($"[RpcApiHub] SendAiMessage: {preview} (Session: {sessionId})");
                AnyCommandReceived?.Invoke(this, $"AI Message Sent: {preview} (Session: {sessionId})");
                
                int targetPid = sessionId ?? -1;

                // 1. Broadcast the user message so other clients can see it
                await Clients.All.SendAsync("ReceiveAiMessage", Context.ConnectionId, message, targetPid);

                string connectionId = Context.ConnectionId;

                // 2. Direct Hub-to-CLI communication (Backgrounded to prevent Hub blocking)
                _ = Task.Run(async () =>
                {
                    try
                    {
                        if (_aiCliService.IsBusy)
                        {
                            _logger.LogInformation($"[RpcApiHub] AI is busy, notifying client that message is QUEUED (PID: {targetPid})");
                            await Clients.Caller.SendAsync("ReceiveAiStatus", "QUEUED", targetPid);
                        }

                        if (targetPid != -1)
                        {
                            _logger.LogInformation($"[RpcApiHub] Attempting auto-rename for PID {targetPid}...");
                            _aiCliService.TryAutoRenameSession(targetPid, message);
                            // Notify all clients of updated session names without rediscovery
                            await _hubEventSender.BroadcastSessions();
                        }

                        _logger.LogInformation($"[RpcApiHub] Background task sending prompt to AI (PID: {targetPid})");
                        bool success = await _aiCliService.SendPromptAsync(message, targetPid);
                        if (!success)
                        {
                            _logger.LogWarning($"[RpcApiHub] AI Communication Failed for PID {targetPid}");
                            await _hubEventSender.SendAiError(connectionId, "Error: Failed to communicate with AI service.", targetPid);
                            AnyCommandReceived?.Invoke(this, "AI Communication Failed");
                        }
                    }
                    catch (Exception ex)
                    {
                        _logger.LogError(ex, $"[RpcApiHub] Error in background AI prompt task for PID {targetPid}");
                    }
                });
            }
        }

        public async Task SendAiSpecialKey(string key, int? sessionId = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                int targetPid = sessionId ?? _aiCliService.GetTargetPid();
                if (targetPid == -1) return;

                _logger.LogInformation($"[RpcApiHub] SendAiSpecialKey: {key} to PID {targetPid}");
                await _aiCliService.FocusSessionAsync(targetPid);
                await Task.Delay(150); // Give OS time to focus

                switch (key.ToLower())
                {
                    case "escape":
                        _inputService.SendKeyPress(0x1B);
                        break;
                    case "enter":
                        _inputService.SendKeyPress(0x0D);
                        break;
                    case "up":
                        _inputService.SendKeyPress(0x26);
                        break;
                    case "down":
                        _inputService.SendKeyPress(0x28);
                        break;
                    case "left":
                        _inputService.SendKeyPress(0x25);
                        break;
                    case "right":
                        _inputService.SendKeyPress(0x27);
                        break;
                    case "yolo":
                        await SendAiYolo(targetPid); // Re-use the yolo logic
                        break;
                    default:
                        // Fallback to pipe for other special keys if necessary, or log a warning
                        _logger.LogWarning($"[RpcApiHub] Unknown special key '{key}' for direct SendKeyPress.");
                        await _aiCliService.SendSpecialKeyAsync(key, targetPid);
                        break;
                }
            }
        }

        public async Task SendAiDialogResponse(string response, int? sessionId = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                int targetPid = sessionId ?? _aiCliService.GetTargetPid();
                if (targetPid == -1) return;

                _logger.LogInformation($"[RpcApiHub] SendAiDialogResponse: {response} to PID {targetPid}");
                await _aiCliService.FocusSessionAsync(targetPid);
                await Task.Delay(150);
                await _aiCliService.SendDialogResponseAsync(response, targetPid);
            }
        }

        public async Task SendAiYolo(int? sessionId = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                int targetPid = sessionId ?? _aiCliService.GetTargetPid();
                if (targetPid == -1) return;

                _logger.LogInformation($"[RpcApiHub] Sending YOLO to PID {targetPid}");
                await _aiCliService.FocusSessionAsync(targetPid);
                await Task.Delay(150); // Give OS time to focus

                _inputService.KeyDown(0x11); // VK_CONTROL
                _inputService.SendKeyPress(0x59); // VK_Y
                await Task.Delay(50);
                _inputService.KeyUp(0x11); // VK_CONTROL
            }
        }

        public async Task SendAiKeyEvent(int pid, ushort keyCode, bool ctrl = false, bool shift = false, bool alt = false)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _processService.WinActivatePid(pid);
                await Task.Delay(100);
                
                if (ctrl) _inputService.KeyDown(0x11);
                if (shift) _inputService.KeyDown(0x10);
                if (alt) _inputService.KeyDown(0x12);
                
                _inputService.SendKeyPress(keyCode);
                
                if (alt) _inputService.KeyUp(0x12);
                if (shift) _inputService.KeyUp(0x10);
                if (ctrl) _inputService.KeyUp(0x11);
            }
        }

        public async Task SendAiResponse(string response, int? pid = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                int targetPid = pid ?? _aiCliService.GetTargetPid();
                _logger.LogDebug($"[RpcApiHub] SendAiResponse received for PID {targetPid}");
                AnyCommandReceived?.Invoke(this, "AI Response Received");
                await Clients.All.SendAsync("ReceiveAiResponse", response, targetPid);
            }
        }

        public async Task SendAiStatus(string status, int? pid = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                int targetPid = pid ?? _aiCliService.GetTargetPid();
                _logger.LogDebug($"[RpcApiHub] SendAiStatus: {status} for PID {targetPid}");
                await Clients.All.SendAsync("ReceiveAiStatus", status, targetPid);
            }
        }

        public async Task SendAiHubCommand(string command, JsonElement payload)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] SendAiHubCommand: {command}");
                AnyCommandReceived?.Invoke(this, $"AI HUB COMMAND: {command}");
                try
                {
                    _commandDispatcher.Dispatch(command, payload);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, $"Error dispatching AI Hub command '{command}'");
                }
            }
        }

        public async Task<string> ProcessMacro(string script)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "ProcessMacro");
                var lines = script.Split('\n');
                var modified = false;
                var settings = _authService.GetType() == typeof(AuthService) ? // Hacky access to settings service if not injected directly? 
                    Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService : null;

                if (settings == null) return script; // Should not happen

                for (int i = 0; i < lines.Length; i++)
                {
                    var line = lines[i].Trim();
                    if (line.StartsWith("run ", StringComparison.OrdinalIgnoreCase))
                    {
                        var arg = line.Substring(4).Trim();
                        // Check if it's already a full path
                        if (arg.Contains(":\\") || arg.StartsWith("/"))
                        {
                            if (arg.Contains(" ") && !arg.StartsWith("\""))
                            {
                                lines[i] = $"run \"{arg}\"";
                                modified = true;
                            }
                            continue;
                        }
                        if (arg.StartsWith("http", StringComparison.OrdinalIgnoreCase)) continue;

                        // Check mapping
                        var mapped = settings.GetPath(arg);
                        if (mapped != null)
                        {
                            // Already mapped, resolution logic
                            var finalPath = mapped.Contains(" ") && !mapped.StartsWith("\"") ? $"\"{mapped}\"" : mapped;
                            lines[i] = $"run {finalPath}";
                            modified = true;
                        }
                        else
                        {
                            // Not mapped, search
                            var foundPath = _fileService.FindExecutable(arg);
                            if (foundPath != null)
                            {
                                settings.AddMapping(arg, foundPath);
                                var finalPath = foundPath.Contains(" ") && !foundPath.StartsWith("\"") ? $"\"{foundPath}\"" : foundPath;
                                lines[i] = $"run {finalPath}";
                                modified = true;
                                await Clients.Caller.SendAsync("ReceiveCommandOutput", $"[Macro] Resolved '{arg}' to '{foundPath}'");
                            }
                        }
                    }
                }

                return modified ? string.Join("\n", lines) : script;
            }
            throw new UnauthorizedAccessException();
        }

        public void WinActivate(string target)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"WinActivate: {target}");
                _processService.WinActivate(target);
            }
        }

        public void WinClose(string target)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"WinClose: {target}");
                _processService.WinClose(target);
            }
        }

        public async Task<int?> StartNewAiSession(string? workspace = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                try
                {
                    _logger.LogInformation($"[RpcApiHub] StartNewAiSession requested (Workspace: {workspace}). Broadcasting status...");
                    AnyCommandReceived?.Invoke(this, $"StartNewAiSession requested (Workspace: {workspace})");
                    await Clients.All.SendAsync("ReceiveAiHistory", "[]", -1);
                    await Clients.All.SendAsync("ReceiveAiStatus", "Starting session...", -1);
                    
                    _logger.LogInformation("[RpcApiHub] Calling AiCliService.LaunchSessionAsync...");
                    
                    var result = await _aiCliService.LaunchSessionAsync(workspace, (status) => 
                    {
                        AnyCommandReceived?.Invoke(this, $"AI Launch: {status}");
                    });
                    
                    _logger.LogInformation($"[RpcApiHub] StartNewAiSession result: {result}");

                    if (result.HasValue)
                    {
                        AnyCommandReceived?.Invoke(this, $"AI Launch Success: PID {result.Value}");
                        await Clients.All.SendAsync("ReceiveNewAiSessionPid", result.Value);
                        // Clear the status on client side
                        await SendAiStatus("FINISHED", result.Value);
                    }
                    else
                    {
                        AnyCommandReceived?.Invoke(this, "AI Launch Failed");
                        await Clients.All.SendAsync("ReceiveAiStatus", "Failed to start session", -1);
                    }
                    return result;
                }
                catch (Exception ex)
                {
                     _logger.LogError(ex, "[RpcApiHub] Error in StartNewAiSession");
                     AnyCommandReceived?.Invoke(this, $"AI Launch Error: {ex.Message}");
                     await Clients.All.SendAsync("ReceiveAiStatus", "Error starting session", -1);
                     return null;
                }
            }
            return null;
        }

        public async Task<int?> StartCliAtWorkspace(string path)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] StartCliAtWorkspace requested: {path}");
                AnyCommandReceived?.Invoke(this, $"StartCliAtWorkspace: {path}");
                await Clients.All.SendAsync("ReceiveAiHistory", "[]", -1);
                await Clients.All.SendAsync("ReceiveAiStatus", "Starting session...", -1);
                var result = await _aiCliService.LaunchSessionAsync(path);
                _logger.LogInformation($"[RpcApiHub] StartCliAtWorkspace result: {result}");
                if (result.HasValue)
                {
                    await Clients.All.SendAsync("ReceiveNewAiSessionPid", result.Value);
                    // Clear the status on client side
                    await SendAiStatus("FINISHED", result.Value);
                }
                return result;
            }
            return null;
        }

        public async Task StopAiSession(int pid)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] StopAiSession: {pid}");
                AnyCommandReceived?.Invoke(this, $"StopAiSession: {pid}");
                await _aiCliService.StopSessionAsync(pid);
                
                await GetAiSessions();
            }
        }

        public async Task RenameAiSession(int pid, string name)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"RenameAiSession: {pid} -> {name}");
                AnyCommandReceived?.Invoke(this, $"RenameAiSession: {pid} -> {name}");
                await _aiCliService.SetSessionNameAsync(pid, name);
                await GetAiSessions();
            }
        }

        public async Task ResetAiSessions()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation("[RpcApiHub] ResetAiSessions requested. Nuking all Gemini processes...");
                AnyCommandReceived?.Invoke(this, "ResetAiSessions");
                _aiCliService.KillAllGeminiProcesses();
                await _aiCliService.DiscoverSessionsAsync();
                await _hubEventSender.BroadcastSessions();
            }
        }

        public async Task ReloadAiSessions(List<AiSessionInfo> androidSessions)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation("[RpcApiHub] ReloadAiSessions requested.");
                AnyCommandReceived?.Invoke(this, "ReloadAiSessions");
                await _aiCliService.ReloadAiSessionsAsync(androidSessions);
                await _hubEventSender.BroadcastSessions();
            }
        }

        public async Task HandleExternalCommand(string command, string payload)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] HandleExternalCommand: {command} with payload: {payload}");
                AnyCommandReceived?.Invoke(this, $"External: {command} ({payload})");

                switch (command)
                {
                    case "OPEN_FILE_ON_ANDROID":
                        if (Directory.Exists(payload))
                        {
                            await _hubEventSender.SendPayloadToAndroid("OPEN_FOLDER", new { Path = payload });
                        }
                        else
                        {
                            await _hubEventSender.SendPayloadToAndroid("OPEN_FILE", new { Path = payload });
                        }
                        break;
                    case "CLI_HERE":
                        _ = Task.Run(async () => {
                             await _aiCliService.LaunchSessionAsync(payload);
                        });
                        break;
                }
            }
        }

        public async Task GetAiSessions()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation("[RpcApiHub] GetAiSessions requested");
                // Request from legacy Python listeners (optional, but keep for compatibility)
                await Clients.All.SendAsync("RequestAiSessions");

                // Discover directly in Hub
                await _aiCliService.DiscoverSessionsAsync();
                await _hubEventSender.BroadcastSessions();
            }
        }

        public async Task SwitchAiSession(int pid)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] SwitchAiSession: {pid}");
                AnyCommandReceived?.Invoke(this, $"SwitchAiSession: {pid}");
                
                // Switch in legacy Python listeners
                await Clients.All.SendAsync("SwitchAiSession", pid);

                // Switch in Hub
                bool success = await _aiCliService.SetTargetPidAsync(pid);
                if (success)
                {
                    await _aiCliService.GetHistoryAsync(pid);
                }
                else
                {
                    await Clients.Caller.SendAsync("ReceiveAiStatus", "Failed to connect", pid);
                }
            }
        }

        public async Task FocusAiSession(int pid)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] FocusAiSession: {pid}");
                AnyCommandReceived?.Invoke(this, $"FocusAiSession: {pid}");
                await _aiCliService.FocusSessionAsync(pid);
            }
        }

        public async Task SetAiZoom(int pid, double level)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] SetAiZoom: {pid} -> {level}");
                AnyCommandReceived?.Invoke(this, $"SetAiZoom: {level}");
                
                // Emulate hardware zoom (Ctrl + Scroll)
                _inputService.SetZoom(level > 1.0);
            }
        }

        public async Task ReceiveAiSessions(List<int> pids)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                await Clients.All.SendAsync("ReceiveAiSessions", pids);
            }
        }

        public async Task RequestAiHistory(int? pid = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                int targetPid = pid ?? _aiCliService.GetTargetPid();
                _logger.LogInformation($"[RpcApiHub] RequestAiHistory for PID {targetPid}");
                await Clients.All.SendAsync("ReceiveAiStatus", "Reloading history...", targetPid);
                await _aiCliService.GetHistoryAsync(targetPid);
            }
        }

        public async Task GetAiPresets()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var presets = _settingsService.GetAiPresets();
                await Clients.Caller.SendAsync("ReceiveAiPresets", presets);
            }
        }

        public async Task AddAiPreset(string preset)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _settingsService.AddAiPreset(preset);
                var presets = _settingsService.GetAiPresets();
                await Clients.All.SendAsync("ReceiveAiPresets", presets);
            }
        }

        public async Task RemoveAiPreset(string preset)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _settingsService.RemoveAiPreset(preset);
                var presets = _settingsService.GetAiPresets();
                await Clients.All.SendAsync("ReceiveAiPresets", presets);
            }
        }

        public async Task ReceiveAiHistory(string historyJson)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                await Clients.All.SendAsync("ReceiveAiHistory", historyJson);
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

        public async Task SetCortexWakeTime(string wakeTime)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"Set Cortex Wake Time: {wakeTime}");
                await Clients.All.SendAsync("UpdateCortexWakeTime", wakeTime);
            }
        }

        public async Task SetCortexTemplates(string templatesJson)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "Set Cortex Templates");
                await Clients.All.SendAsync("UpdateCortexTemplates", templatesJson);
            }
        }

        public void PcgSaveState(string worldId, float x, float y, string data, bool isExclusion)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"PcgSaveState: {worldId} at ({x}, {y})");
                _pcgService.SaveObjectState(worldId, x, y, data, isExclusion);
            }
        }

        public PcgObjectState? PcgGetState(string worldId, float x, float y)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                return _pcgService.GetObjectState(worldId, x, y);
            }
            return null;
        }

        public List<PcgObjectState> PcgGetAllStatesForWorld(string worldId)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                return _pcgService.GetAllStatesForWorld(worldId);
            }
            return new List<PcgObjectState>();
        }

        public async Task ExecuteMacro(JsonElement commands)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "ExecuteMacro");
                await _processService.ExecuteMacro(commands, _inputService, _clipboardService);
            }
        }

        public async Task TriggerTellPc()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation("[RpcApiHub] TriggerTellPc requested.");
                AnyCommandReceived?.Invoke(this, "TriggerTellPc");

                string workspace = _settingsService.Settings.TellPcWorkspace;
                string systemContext = _settingsService.Settings.TellPcSystemContext;

                await Clients.All.SendAsync("ReceiveAiStatus", "Tell PC: Starting Session...", -1);

                _logger.LogInformation($"[RpcApiHub] Launching Tell PC session in workspace: {workspace}");
                _aiCliService.SetTellPcContext(-1, systemContext); // Mark as pending Tell PC context
                var pid = await _aiCliService.LaunchSessionAsync(workspace);

                if (pid.HasValue)
                {
                    _logger.LogInformation($"[RpcApiHub] Tell PC session started with PID {pid.Value}. Setting context.");
                    _aiCliService.SetTellPcContext(pid.Value, systemContext);
                    await Clients.All.SendAsync("ReceiveNewAiSessionPid", pid.Value);
                    await Clients.All.SendAsync("ReceiveAiStatus", "FINISHED", pid.Value);
                    // READY_TO_LISTEN is now handled by Android side flow emission
                }
                else
                {
                    _logger.LogWarning("[RpcApiHub] Failed to start Tell PC session.");
                    await Clients.All.SendAsync("ReceiveAiStatus", "FAILED_TO_START_TELL_PC", -1);
                }
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


