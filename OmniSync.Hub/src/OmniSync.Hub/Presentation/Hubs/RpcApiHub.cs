using Microsoft.AspNetCore.SignalR;
using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Infrastructure.Services; // Added for FileService
using OmniSync.Hub.Models;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Logic.Services.VoiceInbox;
using OmniSync.Hub.Logic.Services.AssistantCommands;
using Microsoft.Extensions.Logging; // Added for ILogger

namespace OmniSync.Hub.Presentation.Hubs
{
    public class SendPayloadRequest
    {
        public string Command { get; set; } = string.Empty;
        public object? Payload { get; set; }
    }

    public class MouseMoveRequest
    {
        public double X { get; set; }
        public double Y { get; set; }
    }

    public class RpcApiHub : Microsoft.AspNetCore.SignalR.Hub, IDisposable
    {
        // New: Event to notify of any command received by the Hub
        public static event EventHandler<string>? AnyCommandReceived;

        // New: Events for client connection/disconnection
        public static event EventHandler<string>? ClientConnectedEvent;
        public static event EventHandler<string>? ClientDisconnectedEvent;

        // One in-flight send per session; guards double-taps from the phone.

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
        private readonly GitService _gitService;
        private readonly GlobalHotkeyService _hotkeyService;
        private readonly IMacroService _macroService;
        private readonly BookProgressService _bookProgressService;
        private readonly AndroidUpdateDeploymentCoordinator _androidUpdateCoordinator;
        private readonly AgentSessionService _agentSessionService;
        private readonly ExternalIpService _externalIpService;
        private readonly IReminderRepository _reminderRepository;
        private readonly VoiceInboxService _voiceInboxService;
        private readonly AssistantDeviceRegistry _assistantDevices;
        private readonly AssistantRemoteDispatcher _assistantRemoteDispatcher;
        private readonly ILogger<RpcApiHub> _logger; // Added for logging

        public RpcApiHub(AuthService authService, FileService fileService, ClipboardService clipboardService, CommandDispatcher commandDispatcher, ProcessService processService, HubEventSender hubEventSender, InputService inputService, AudioService audioService, ShutdownService shutdownService, RegistryService registryService, HubMonitorService hubMonitorService, AiCliService aiCliService, PcgPersistentService pcgService, HubSettingsService settingsService, GitService gitService, GlobalHotkeyService hotkeyService, IMacroService macroService, BookProgressService bookProgressService, AndroidUpdateDeploymentCoordinator androidUpdateCoordinator, AgentSessionService agentSessionService, ExternalIpService externalIpService, IReminderRepository reminderRepository, VoiceInboxService voiceInboxService, AssistantDeviceRegistry assistantDevices, AssistantRemoteDispatcher assistantRemoteDispatcher, ILogger<RpcApiHub> logger)
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
            _gitService = gitService;
            _hotkeyService = hotkeyService;
            _macroService = macroService;
            _bookProgressService = bookProgressService;
            _androidUpdateCoordinator = androidUpdateCoordinator;
            _agentSessionService = agentSessionService;
            _externalIpService = externalIpService;
            _reminderRepository = reminderRepository;
            _voiceInboxService = voiceInboxService;
            _assistantDevices = assistantDevices;
            _assistantRemoteDispatcher = assistantRemoteDispatcher;
            _logger = logger;
        }

        public override async Task OnConnectedAsync()
        {
            var ip = Context.GetHttpContext()?.Connection.RemoteIpAddress?.ToString();
            var userAgent = Context.GetHttpContext()?.Request.Headers["User-Agent"].ToString();
            _logger.LogInformation($"[RpcApiHub][INIT_DEBUG] Client connecting: {Context.ConnectionId} from IP: {ip}. UA: {userAgent}. Awaiting authentication.");
            ClientConnectedEvent?.Invoke(this, Context.ConnectionId);
            _androidUpdateCoordinator.ClientConnected(Context.ConnectionId, ip);
            _hubEventSender.SubscribeForCommandOutput(Context.UserIdentifier ?? Context.ConnectionId, Context.ConnectionId);
            await base.OnConnectedAsync();
            
            // Send current modifier states to the newly connected client
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                await SendCurrentState();
            }

            // The cached public address goes out on every connect, before the
            // client has authenticated, so a phone that just woke up outside the
            // LAN already has a current address to try.
            if (_externalIpService.CurrentExternalIp is { } externalIp)
            {
                await Clients.Caller.SendAsync("UpdateExternalIp", externalIp, _externalIpService.UpdatedAtUnix);
            }
        }

        public override async Task OnDisconnectedAsync(Exception? exception)
        {
            _logger.LogInformation($"Client disconnected: {Context.ConnectionId}");
            ClientDisconnectedEvent?.Invoke(this, Context.ConnectionId);
            _androidUpdateCoordinator.ClientDisconnected(Context.ConnectionId);
            _assistantDevices.Disconnect(Context.ConnectionId);
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
            await Clients.Caller.SendAsync("ReceiveCleanupPatterns", _settingsService.Settings.BrowserCleanupPatterns);
            await Clients.Caller.SendAsync("ReceiveAiModels", _settingsService.Settings.AiModels);
            await Clients.Caller.SendAsync("ReceiveDefaultAiModel", _settingsService.Settings.DefaultAiModel);
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

        public void ReportTftStatus(bool isActive)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _hubMonitorService.IsTftActive = isActive;
            }
        }

        public async Task<string> GetGitLog(string path, int count = 20)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                return await _gitService.GetGitLog(path, count);
            }
            return "Error: Unauthorized";
        }

        public async Task<string> GetCommitDiff(string path, string commitHash)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                return await _gitService.GetCommitDiff(path, commitHash);
            }
            return "Error: Unauthorized";
        }

        public bool IsGitRepository(string path)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                return _gitService.IsGitRepository(path);
            }
            return false;
        }

        public async Task<bool> Authenticate(string apiKey)
        {
            _logger.LogInformation($"[RpcApiHub] Authenticate requested by {Context.ConnectionId}");
            if (_authService.Validate(apiKey))
            {
                Context.Items["IsAuthenticated"] = true;
                _logger.LogInformation($"[RpcApiHub] Connection {Context.ConnectionId} authenticated successfully.");
                return true;
            }
            _logger.LogWarning($"[RpcApiHub] Connection {Context.ConnectionId} failed authentication. Provided key length: {apiKey?.Length ?? 0}");
            return false;
        }

        public async Task<IReadOnlyList<ReminderItem>> GetReminders()
        {
            EnsureAuthenticated();
            var items = await _reminderRepository.GetAllAsync();
            return items
                .OrderBy(item => item.IsTerminal)
                .ThenBy(item => item.NextFireAt ?? item.DueAt)
                .ToList();
        }

        public async Task<ReminderItem?> CompleteReminder(string reminderId)
        {
            EnsureAuthenticated();
            var id = ParseReminderId(reminderId);
            var now = DateTimeOffset.Now;

            return await _reminderRepository.UpdateAsync(id, item =>
            {
                item.State = ReminderState.Done;
                item.Ownership = ReminderOwnership.Owned;
                item.AcknowledgedAt = now;
                item.CompletedAt = now;
                item.NextFireAt = null;
                return item;
            });
        }

        public async Task<ReminderItem?> DismissReminder(string reminderId)
        {
            EnsureAuthenticated();
            var id = ParseReminderId(reminderId);
            var now = DateTimeOffset.Now;

            return await _reminderRepository.UpdateAsync(id, item =>
            {
                item.State = ReminderState.Dismissed;
                item.Ownership = ReminderOwnership.Owned;
                item.AcknowledgedAt = now;
                item.NextFireAt = null;
                return item;
            });
        }

        public async Task<ReminderItem?> SnoozeReminder(string reminderId, int minutes)
        {
            EnsureAuthenticated();
            if (minutes < 1 || minutes > 10080)
                throw new HubException("Snooze duration must be between 1 minute and 7 days.");

            var id = ParseReminderId(reminderId);
            var now = DateTimeOffset.Now;

            return await _reminderRepository.UpdateAsync(id, item =>
            {
                if (item.IsTerminal)
                    return item;

                item.State = ReminderState.SnoozedOrPostponed;
                item.Ownership = ReminderOwnership.Owned;
                item.AcknowledgedAt = now;
                item.NextFireAt = now.AddMinutes(minutes);
                return item;
            });
        }

        public async Task<ReminderItem?> PostponeReminder(string reminderId, string dueAtIso)
        {
            EnsureAuthenticated();
            if (!DateTimeOffset.TryParse(dueAtIso, out var dueAt))
                throw new HubException("Invalid postpone time.");

            var id = ParseReminderId(reminderId);
            return await _reminderRepository.UpdateAsync(id, item =>
            {
                if (item.IsTerminal)
                    return item;

                item.State = ReminderState.SnoozedOrPostponed;
                item.Ownership = ReminderOwnership.Owned;
                item.DueAt = dueAt;
                item.NextFireAt = dueAt;
                item.AcknowledgedAt = DateTimeOffset.Now;
                return item;
            });
        }

        public Task ConnectWadb(string pairingCode, string pairingPort, string connectPort)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                if (!Regex.IsMatch(pairingCode, @"^\d{6}$"))
                {
                    throw new HubException("The wireless debugging pairing code must contain six digits.");
                }
                if (!IsValidAdbEndpointHint(pairingPort) || !IsValidAdbEndpointHint(connectPort))
                {
                    throw new HubException("ADB endpoints must be a port or an IP:port value.");
                }

                _hubMonitorService.AddLogMessage("[RpcApiHub] Wireless ADB pairing requested.");
                
                // Locate connectadb.py - it's in the project root
                string projectRoot = AppContext.BaseDirectory;
                for (int i = 0; i < 10; i++)
                {
                    if (File.Exists(Path.Combine(projectRoot, "connectadb.py")))
                    {
                        break;
                    }
                    projectRoot = Path.GetDirectoryName(projectRoot) ?? projectRoot;
                }
                
                string scriptPath = Path.Combine(projectRoot, "connectadb.py");
                if (File.Exists(scriptPath))
                {
                    var endpointArguments = string.Empty;
                    if (!string.IsNullOrWhiteSpace(pairingPort)) endpointArguments += $" --pair-endpoint {pairingPort}";
                    if (!string.IsNullOrWhiteSpace(connectPort)) endpointArguments += $" --connect-endpoint {connectPort}";
                    _logger.LogInformation("[RpcApiHub] Starting the wireless ADB helper.");
                    _ = _processService.ExecuteCommandWithLogging(
                        $"python \"{scriptPath}\" --pair-code {pairingCode}{endpointArguments} --non-interactive",
                        "[WIFI-DBG]");
                }
                else
                {
                    _logger.LogError($"[RpcApiHub] Could not find connectadb.py in {projectRoot} or parent directories.");
                }
            }
            return Task.CompletedTask;
        }

        public string GetTaskResurfacingTimes()
        {
            EnsureAuthenticated();
            return _settingsService.Settings.TaskResurfacingTimes;
        }

        public void SetTaskResurfacingTimes(string times)
        {
            EnsureAuthenticated();
            var values = times.Split(',').Select(value => value.Trim()).ToList();
            if (values.Count == 0 || values.Any(value => !TimeOnly.TryParseExact(value, "HH:mm", out _)))
                throw new HubException("Enter comma-separated times in HH:mm format.");
            _settingsService.Settings.TaskResurfacingTimes = string.Join(',', values.Distinct().Order());
            _settingsService.SaveSettings();
            _hubMonitorService.AddLogMessage($"[Reminders] Task resurfacing times set to {_settingsService.Settings.TaskResurfacingTimes}.");
        }

        public void ReportAndroidUpdateStatus(string requestId, string status, string message)
        {
            if (!Context.Items.TryGetValue("IsAuthenticated", out var authenticated) ||
                authenticated is not true)
            {
                throw new UnauthorizedAccessException();
            }
            if (requestId.Length > 64 || status.Length > 64 || message.Length > 1000)
            {
                throw new ArgumentException("Android update status payload is too large.");
            }

            var accepted = _androidUpdateCoordinator.Report(requestId, status, message);
            _logger.LogInformation(
                "[AndroidDeploy] Client {ConnectionId} reported {Status} for {RequestId}; accepted={Accepted}; {Message}",
                Context.ConnectionId,
                status,
                requestId,
                accepted,
                message);
        }

        private static bool IsValidAdbEndpointHint(string value)
        {
            if (string.IsNullOrWhiteSpace(value)) return true;
            return Regex.IsMatch(value, @"^\d{1,5}$") ||
                   Regex.IsMatch(value, @"^[A-Za-z0-9.:\[\]-]+:\d{1,5}$");
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
                    settingsService.UpdateHotkeys(hotkeys);
                    _hubMonitorService.AddLogMessage("Hotkeys updated via web interface.");
                }
            }
            else
            {
                throw new UnauthorizedAccessException();
            }
        }

        public object GetSetupOptions()
        {
            _ = GetSettings(); // Authentication gate shared with settings reads.
            return new {
                monitors = System.Windows.Forms.Screen.AllScreens.Select((s, i) => new { key = s.DeviceName,
                    label = $"Monitor {i + 1}{(s.Primary ? " (primary)" : "")}", layout = OmniSync.Hub.Logic.LayoutHelper.FromRectangle(s.WorkingArea, s) }),
                zones = OmniSync.Hub.Logic.SetupOptions.Zones()
            };
        }

        public async Task LaunchSetup(Guid id)
        {
            var settings = GetSettings();
            var setup = settings.Setups.FirstOrDefault(s => s.Id == id) ?? throw new ArgumentException("Setup not found.");
            var launcher = Context.GetHttpContext()?.RequestServices.GetService(typeof(OmniSync.Hub.Logic.ProjectLauncherService)) as OmniSync.Hub.Logic.ProjectLauncherService;
            if (launcher != null) await launcher.LaunchProject(setup);
        }

        public void RequestSetupSnapshot(Guid? id = null, bool replace = false)
        {
            var settings = GetSettings();
            var setup = id.HasValue ? settings.Setups.FirstOrDefault(s => s.Id == id) ?? throw new ArgumentException("Setup not found.") : null;
            var dispatcher = Context.GetHttpContext()?.RequestServices.GetService(typeof(CommandDispatcher)) as CommandDispatcher;
            dispatcher?.RequestSetupSnapshot(setup, replace);
        }

        public void UpdateSetups(List<Project> projects)
        {
            var authenticatedSettings = GetSettings();
            var globalKeys = authenticatedSettings.Hotkeys.Where(h => !h.Action.StartsWith("LAUNCH_SETUP_") && !string.IsNullOrWhiteSpace(h.Key))
                .Select(h => h.Key).ToHashSet(StringComparer.OrdinalIgnoreCase);
            var used = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var setup in projects)
            {
                if (!string.IsNullOrWhiteSpace(setup.GlobalHotkey) && !globalKeys.Add(setup.GlobalHotkey))
                    throw new ArgumentException("A global hotkey is already assigned.");
                if (!string.IsNullOrWhiteSpace(setup.Shortcut) &&
                    (!OmniSync.Hub.Logic.SetupRules.IsValidShortcut(setup.Shortcut) || !used.Add(setup.Shortcut)))
                    throw new ArgumentException("Setup shortcuts must be unique; WASD is reserved for navigation.");
            }
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var settingsService = Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService;
                if (settingsService != null)
                {
                    settingsService.Settings.Projects = projects;
                    settingsService.SaveSettings();
                    _hubMonitorService.AddLogMessage("Setups updated via web interface.");
                }
            }
            else
            {
                throw new UnauthorizedAccessException();
            }
        }

        public void AddSetup(Project project)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var settingsService = Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService;
                settingsService?.AddProject(project);
            }
        }

        public void UpdateSetup(Project project)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var settingsService = Context.GetHttpContext()?.RequestServices.GetService(typeof(HubSettingsService)) as HubSettingsService;
                settingsService?.UpdateProject(project);
            }
        }

        public void RemoveSetup(Guid id)
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
                    ScheduledShutdownTime = _shutdownService.GetScheduledTime(),
                    ExternalIp = _externalIpService.CurrentExternalIp,
                    ExternalIpSource = _externalIpService.CurrentSource,
                    ExternalIpUpdatedAtUnix = _externalIpService.UpdatedAtUnix
                };
            }
            throw new UnauthorizedAccessException();
        }

        /// <summary>Re-resolves the public address now and pushes it to every client.</summary>
        public async Task<string?> RefreshExternalIp()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                return await _externalIpService.RefreshAsync(Context.ConnectionAborted);
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

        public void SendPayload(string command, object payload)
        {
            // Invoke the event for SendPayload commands
            if (!string.IsNullOrEmpty(command))
            {
                AnyCommandReceived?.Invoke(this, command);

                try
                {
                    _commandDispatcher.Dispatch(command, payload);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, $"Error dispatching command '{command}'");
                }
            }
        }

        public void AdvertiseAssistantCommands(
            string deviceId,
            IReadOnlyList<DeviceAssistantCommandDescriptor> commands)
        {
            EnsureAuthenticated();
            if (string.IsNullOrWhiteSpace(deviceId))
                throw new HubException("Device id is required.");

            _assistantDevices.Advertise(
                deviceId.Trim(),
                Context.ConnectionId,
                commands ?? Array.Empty<DeviceAssistantCommandDescriptor>());

            _hubMonitorService.AddLogMessage(
                $"[Assistant] Device {deviceId} advertised {commands?.Count ?? 0} command(s).");
        }

        public async Task<bool> ReportAssistantRunResult(
            string runId,
            string state,
            string? summary = null)
        {
            EnsureAuthenticated();
            if (!Guid.TryParse(runId, out var parsedRunId))
                throw new HubException("Invalid assistant run id.");

            return await _assistantRemoteDispatcher.ReportResultAsync(
                parsedRunId,
                state,
                summary);
        }

        public async Task<VoiceInboxOutcome?> SendJarvisTranscript(string transcript)
        {
            EnsureAuthenticated();

            transcript = (transcript ?? string.Empty).Trim();
            if (transcript.Length == 0) return null;

            _logger.LogInformation(
                "[RpcApiHub] Jarvis transcript received ({Length} chars).",
                transcript.Length);
            AnyCommandReceived?.Invoke(this, "JarvisVoice");

            return await _voiceInboxService.SubmitAsync(
                new VoiceInboxItem(
                    transcript,
                    "android-voice",
                    null,
                    DateTimeOffset.Now),
                CancellationToken.None);
        }

        public async Task<VoiceInboxOutcome> SubmitAssistantRequest(
            string requestId,
            string transcript,
            long occurredAtUnixMillis,
            string? conversationId = null)
        {
            EnsureAuthenticated();
            if (!Guid.TryParse(requestId, out var parsedRequestId))
                throw new HubException("Invalid assistant request id.");

            transcript = (transcript ?? string.Empty).Trim();
            if (transcript.Length == 0)
                throw new HubException("Assistant request text is required.");

            var occurredAt = DateTimeOffset.FromUnixTimeMilliseconds(occurredAtUnixMillis);
            return await _voiceInboxService.SubmitAsync(
                new VoiceInboxItem(
                    transcript,
                    "android-voice",
                    null,
                    DateTimeOffset.Now,
                    OccurredAt: occurredAt,
                    RequestId: parsedRequestId,
                    ConversationId: conversationId),
                CancellationToken.None);
        }

        public void SendTypeTranscript(string transcript)
        {
            EnsureAuthenticated();
            if (string.IsNullOrEmpty(transcript)) return;
            try
            {
                _inputService.SendText(transcript);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error typing voice transcript on PC.");
            }
        }

        public void MouseMove(double x, double y)
        {
            AnyCommandReceived?.Invoke(this, "MouseMove");

            try
            {
                int ix = (int)Math.Round(x);
                int iy = (int)Math.Round(y);
                _inputService.MoveMouse(ix, iy);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error moving mouse");
            }
        }

        public int GetScreenCount()
        {
            if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                throw new UnauthorizedAccessException();

            return System.Windows.Forms.Screen.AllScreens.Length;
        }

        public void CycleFocusedWindowMonitor()
        {
            if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
            {
                throw new UnauthorizedAccessException();
            }

            AnyCommandReceived?.Invoke(this, "CycleFocusedWindowMonitor");
            if (!_processService.CycleForegroundWindowMonitor())
            {
                _logger.LogInformation("Cannot cycle the focused window monitor because fewer than two monitors are available or no foreground window exists.");
            }
        }

        public string GetClipboardText()
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
                    _logger.LogError(ex, "Error setting clipboard text");
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
                    _logger.LogError(ex, "Error pasting clipboard text");
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
                    case "reload_chrome_extension":
                        await Clients.All.SendAsync("ReceiveBrowserCommand", "ReloadExtension", "", false);
                        await Clients.Caller.SendAsync("ReceiveCommandOutput", "Reload command sent to Chrome Extension.");
                        break;
                    case "refresh_browser":
                        string targetUrl = args.Count > 0 ? args[0] : "";
                        // Extensionless (dev-sync.js)
                        await Clients.All.SendAsync("ReceiveDevRefresh", targetUrl);
                        // Chrome Extension (background.js)
                        await Clients.All.SendAsync("ReceiveBrowserCommand", "Refresh", targetUrl, false);
                        await Clients.Caller.SendAsync("ReceiveCommandOutput", string.IsNullOrEmpty(targetUrl) 
                            ? "Dev Refresh command sent (active local tab)." 
                            : $"Dev Refresh command for '{targetUrl}' sent.");
                        break;
                    case "move_window_opposite":
                        if (args.Count > 0)
                        {
                            var payload = new Dictionary<string, object>();
                            if (int.TryParse(args[0], out int pid))
                            {
                                payload["Pid"] = pid;
                            }
                            else
                            {
                                payload["Title"] = args[0];
                            }
                            _commandDispatcher.Dispatch("MOVE_WINDOW_OPPOSITE", payload);
                            await Clients.Caller.SendAsync("ReceiveCommandOutput", $"MOVE_WINDOW_OPPOSITE dispatched for: {args[0]}");
                            await Clients.Caller.SendAsync("CommandExecutionCompleted", command);
                        }
                        else
                        {
                            await Clients.Caller.SendAsync("ReceiveCommandOutput", "Usage: MOVE_WINDOW_OPPOSITE <PID|Title>");
                        }
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

        public async Task<IEnumerable<FileSystemEntry>> ScanBooksRecursive(string rootPath)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new HubException("Unauthorized");
                }

                AnyCommandReceived?.Invoke(this, $"ScanBooksRecursive: {rootPath}");
                var contents = _fileService.ScanBooksRecursive(rootPath);
                return await Task.FromResult(contents);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error scanning books recursively: {rootPath}");
                throw new HubException($"Error scanning books: {ex.Message}");
            }
        }

        public async Task SaveBookProgress(BookProgress progress)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new HubException("Unauthorized");
                }

                AnyCommandReceived?.Invoke(this, $"SaveBookProgress: {progress.BookPath}");
                await _bookProgressService.SaveProgressAsync(progress);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error saving book progress for: {progress.BookPath}");
            }
        }

        public async Task<BookProgress?> GetBookProgress(string bookPath)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new HubException("Unauthorized");
                }

                AnyCommandReceived?.Invoke(this, $"GetBookProgress: {bookPath}");
                return await _bookProgressService.GetProgressAsync(bookPath);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error getting book progress for: {bookPath}");
                return null;
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

        public void AppendToFile(string path, string content)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"AppendToFile: {path}");
                _fileService.AppendToFile(path, content);
            }
        }

        public void CreateDirectory(string path)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, $"CreateDirectory: {path}");
                _fileService.CreateDirectory(path);
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
                
                // Persist on server
                _settingsService.Settings.BrowserCleanupPatterns = patterns;
                _settingsService.SaveSettings();

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

                // 2. Reset bubble state on all clients for this session so the response starts a new bubble
                await Clients.All.SendAsync("ReceiveAiStatus", "RESET_BUBBLE", targetPid);

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
                            _logger.LogInformation($"[RpcApiHub] Background task sending prompt to AI (PID: {targetPid})");
                        }
                        
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

                string workspace = _aiCliService.GetActiveSessions().FirstOrDefault(s => s.Pid == targetPid)?.Workspace ?? "Unknown";
                _logger.LogInformation($"[RpcApiHub] SendAiSpecialKey: '{key}' for PID {targetPid} (Workspace: {workspace})");
                _hubMonitorService.AddLogMessage($"[AI] Key '{key}' sent to PID {targetPid} ({workspace})");

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

                string workspace = _aiCliService.GetActiveSessions().FirstOrDefault(s => s.Pid == targetPid)?.Workspace ?? "Unknown";
                _logger.LogInformation($"[RpcApiHub] SendAiDialogResponse: {response} to PID {targetPid} ({workspace})");
                _hubMonitorService.AddLogMessage($"[AI] Dialog response '{response}' sent to PID {targetPid} ({workspace})");

                await _aiCliService.FocusSessionAsync(targetPid);
                await Task.Delay(150);
                string? effectiveDialogType = _aiCliService.GetLastDialogType(targetPid);
                await _aiCliService.SendDialogResponseAsync(response, targetPid, effectiveDialogType);
            }
        }

        public async Task SendAiYolo(int? sessionId = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                int targetPid = sessionId ?? _aiCliService.GetTargetPid();
                if (targetPid == -1) return;

                string workspace = _aiCliService.GetActiveSessions().FirstOrDefault(s => s.Pid == targetPid)?.Workspace ?? "Unknown";
                _logger.LogInformation($"[RpcApiHub] Sending YOLO to PID {targetPid} ({workspace})");
                _hubMonitorService.AddLogMessage($"[AI] YOLO sent to PID {targetPid} ({workspace})");

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
                string? hint = null;
                if (_aiCliService.GetSessionsWithNames().TryGetValue(pid, out var name)) hint = name;
                
                _processService.WinActivatePid(pid, hint);
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

        private static readonly SemaphoreSlim _aiLaunchSemaphore = new(1, 1);

        [HubMethodName("StartNewAiSession")]
        public async Task<int?> StartNewAiSession(string? workspace = null, string? model = null)
        {
            _logger.LogInformation($"[RpcApiHub] StartNewAiSession call received. Workspace={workspace}, Model={model}, Authenticated={Context.Items.ContainsKey("IsAuthenticated")}");
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                await _aiLaunchSemaphore.WaitAsync();
                try
                {
                    _logger.LogInformation($"[RpcApiHub][INIT_DEBUG] StartNewAiSession requested by {Context.ConnectionId} (Workspace: {workspace}, Model: {model}). Broadcasting status...");
                    AnyCommandReceived?.Invoke(this, $"StartNewAiSession requested (Workspace: {workspace}, Model: {model})");
                    await Clients.All.SendAsync("ReceiveAiHistory", "[]", -1);
                    await Clients.All.SendAsync("ReceiveAiStatus", "Starting session...", -1);
                    
                    _logger.LogInformation($"[RpcApiHub][INIT_DEBUG] Calling AiCliService.LaunchSessionAsync for connection {Context.ConnectionId}...");
                    
                    var result = await _aiCliService.LaunchSessionAsync(workspace, (status) => 
                    {
                        AnyCommandReceived?.Invoke(this, $"AI Launch: {status}");
                    }, model);
                    
                    _logger.LogInformation($"[RpcApiHub][INIT_DEBUG] StartNewAiSession result: {result}");

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
                finally
                {
                    _aiLaunchSemaphore.Release();
                }
            }
            return null;
        }

        [HubMethodName("StartNewAiSessionAndroid")]
        public async Task<int?> StartNewAiSessionAndroid(string? workspace)
        {
            _logger.LogInformation($"[RpcApiHub] StartNewAiSessionAndroid hit. Calling main method...");
            return await StartNewAiSession(workspace, null);
        }

        [HubMethodName("StartCliAtWorkspace")]
        public async Task<int?> StartCliAtWorkspace(string path, string? model = null)
        {
            _logger.LogInformation($"[RpcApiHub] StartCliAtWorkspace call received. Path={path}, Model={model}, Authenticated={Context.Items.ContainsKey("IsAuthenticated")}");
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] StartCliAtWorkspace requested: {path} (Model: {model})");
                AnyCommandReceived?.Invoke(this, $"StartCliAtWorkspace: {path} (Model: {model})");
                await Clients.All.SendAsync("ReceiveAiHistory", "[]", -1);
                await Clients.All.SendAsync("ReceiveAiStatus", "Starting session...", -1);
                var result = await _aiCliService.LaunchSessionAsync(path, null, model);
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

        [HubMethodName("StartCliAtWorkspaceAndroid")]
        public async Task<int?> StartCliAtWorkspaceAndroid(string path)
        {
            _logger.LogInformation($"[RpcApiHub] StartCliAtWorkspaceAndroid hit. Calling main method...");
            return await StartCliAtWorkspace(path, null);
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
                    case "SHOW_WINDOW":
                        _hotkeyService.RequestOpenWindow();
                        break;
                }
            }
        }

        public async Task GetAiSessions()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] GetAiSessions requested by {Context.ConnectionId}");
                // Request from legacy Python listeners (optional, but keep for compatibility)
                await Clients.All.SendAsync("RequestAiSessions");

                // Discover directly in Hub
                await _aiCliService.DiscoverSessionsAsync();
                await _hubEventSender.BroadcastSessions();
            }
        }

        public async Task SwitchAiSession(int pid, int maxChars = 0)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _logger.LogInformation($"[RpcApiHub] SwitchAiSession: {pid} (maxChars: {maxChars})");
                AnyCommandReceived?.Invoke(this, $"SwitchAiSession: {pid}");
                
                // Switch in Hub
                bool success = await _aiCliService.SetTargetPidAsync(pid);
                if (success)
                {
                    await _aiCliService.GetHistoryAsync(pid, maxChars);
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
                string workspace = _aiCliService.GetActiveSessions().FirstOrDefault(s => s.Pid == pid)?.Workspace ?? "Unknown";
                _logger.LogInformation($"[RpcApiHub] FocusAiSession: {pid} (Workspace: {workspace})");
                _hubMonitorService.AddLogMessage($"[AI] Focus requested for PID {pid} ({workspace})");
                AnyCommandReceived?.Invoke(this, $"FocusAiSession: {pid}");
                await _aiCliService.FocusSessionAsync(pid);
            }
        }

                public async Task MoveAiSessionToMonitor(int pid, int monitorIndex)        
                {
                    if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
                    {
                        _logger.LogInformation($"[RpcApiHub] MoveAiSessionToMonitor: PID {pid} to monitor {monitorIndex}");
                        AnyCommandReceived?.Invoke(this, $"MoveAiSessionToMonitor: {pid} -> {monitorIndex}");
                        await _aiCliService.MoveSessionToMonitorAsync(pid, monitorIndex);  
                    }
                    else
                    {
                        _logger.LogWarning($"[RpcApiHub] MoveAiSessionToMonitor: Authentication failed for connection {Context.ConnectionId}");
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

        public async Task RequestAiHistory(int? pid = null, int maxChars = 0)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                int targetPid = pid ?? _aiCliService.GetTargetPid();
                _logger.LogInformation($"[RpcApiHub] RequestAiHistory for PID {targetPid} (maxChars: {maxChars})");
                await Clients.All.SendAsync("ReceiveAiStatus", "Reloading history...", targetPid);
                await _aiCliService.GetHistoryAsync(targetPid, maxChars);
            }
        }

        public async Task RequestAgentSessions()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                try
                {
                    var sessions = _agentSessionService.Listing();
                    _logger.LogInformation("[RpcApiHub] RequestAgentSessions: {Count} transcripts", sessions.Count);
                    await Clients.Caller.SendAsync("ReceiveAgentSessions", JsonSerializer.Serialize(sessions));
                }
                catch (Exception error)
                {
                    _logger.LogError(error, "[RpcApiHub] RequestAgentSessions failed");
                    await Clients.Caller.SendAsync("ReceiveAgentSessions", "[]");
                }
            }
        }

        public async Task RequestAgentSessionDetail(string id, int limit = 120, int before = 0)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                try
                {
                    var detail = _agentSessionService.Read(id, limit, before);
                    await Clients.Caller.SendAsync("ReceiveAgentSessionDetail", JsonSerializer.Serialize(detail));
                }
                catch (Exception error)
                {
                    _logger.LogWarning(error, "[RpcApiHub] RequestAgentSessionDetail failed for {Id}", id);
                    await Clients.Caller.SendAsync("ReceiveAgentSessionDetail", JsonSerializer.Serialize(new
                    {
                        Id = id,
                        Title = "Session unavailable",
                        Events = Array.Empty<object>(),
                        TotalEvents = 0,
                        HasMoreBefore = false,
                        Control = false,
                    }));
                }
            }
        }

        /// <param name="mode">"auto" (send now, or queue while a turn runs), "queue", or "now" (parallel turn while another client is mid-turn).</param>
        public async Task SendAgentMessage(string id, string message, string mode)
        {
            if (!(Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)) return;
            if (string.IsNullOrWhiteSpace(id) || string.IsNullOrWhiteSpace(message)) return;
            _logger.LogInformation("[RpcApiHub] SendAgentMessage to {Id} ({Length} chars, mode {Mode})", id, message.Length, mode);
            AnyCommandReceived?.Invoke(this, $"Agent message sent to {id}");
            await RunAgentControl(id, () => _agentSessionService.Send(id, message, string.IsNullOrWhiteSpace(mode) ? "auto" : mode));
        }

        public async Task CancelQueuedAgentMessage(string id, string queuedId)
        {
            if (!(Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)) return;
            if (!_agentSessionService.CancelQueued(id, queuedId))
            {
                await Clients.Caller.SendAsync("ReceiveAgentSendStatus", JsonSerializer.Serialize(new
                { Id = id, State = "error", Error = "That message was already sent." }));
            }
        }

        public async Task RetryAgentTurn(string id)
        {
            if (!(Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)) return;
            _logger.LogInformation("[RpcApiHub] RetryAgentTurn {Id}", id);
            await RunAgentControl(id, () => _agentSessionService.Retry(id));
        }

        public async Task InterruptAgentTurn(string id)
        {
            if (!(Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)) return;
            _logger.LogInformation("[RpcApiHub] InterruptAgentTurn {Id}", id);
            if (!_agentSessionService.Interrupt(id))
            {
                await Clients.Caller.SendAsync("ReceiveAgentSendStatus", JsonSerializer.Serialize(new
                { Id = id, State = "error", Error = "Only turns started from Omni can be stopped." }));
            }
        }

        public async Task CreateAgentSession(string provider, string cwd, string message)
        {
            if (!(Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)) return;
            if (string.IsNullOrWhiteSpace(message)) return;
            _logger.LogInformation("[RpcApiHub] CreateAgentSession {Provider} in {Cwd}", provider, cwd);
            await RunAgentControl("new", () => _agentSessionService.Create(provider, cwd, message));
        }

        public async Task RenameAgentSession(string id, string title)
        {
            if (!(Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)) return;
            if (string.IsNullOrWhiteSpace(id)) return;
            _logger.LogInformation("[RpcApiHub] RenameAgentSession {Id}", id);
            await RunAgentControl(id, () => _agentSessionService.Rename(id, title ?? ""));
        }

        /// <summary>Permanently deletes the session's transcript files on this PC.</summary>
        public async Task DeleteAgentSession(string id)
        {
            if (!(Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)) return;
            if (string.IsNullOrWhiteSpace(id)) return;
            _logger.LogInformation("[RpcApiHub] DeleteAgentSession {Id}", id);
            AnyCommandReceived?.Invoke(this, $"Agent session deleted: {id}");
            await RunAgentControl(id, () => _agentSessionService.Delete(id));
        }

        /// <summary>Agent control calls return immediately; failures to start are reported to the caller only.</summary>
        private async Task RunAgentControl(string id, Action action)
        {
            try
            {
                action();
            }
            catch (Exception error)
            {
                _logger.LogWarning(error, "[RpcApiHub] Agent control failed for {Id}", id);
                await Clients.Caller.SendAsync("ReceiveAgentSendStatus", JsonSerializer.Serialize(new
                { Id = id, State = "error", Error = TruncateError(error.Message) }));
            }
        }

        private static string TruncateError(string value) =>
            value.Length <= 300 ? value : value[..300];

        public async Task RequestAgentModelStatus()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var provider = _aiCliService.GetTargetPid() >= 0 ? "active" : "idle";
                await Clients.Caller.SendAsync("ReceiveAgentModelStatus", provider);
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

        public async Task GetAiModels()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var models = _settingsService.GetAiModels();
                await Clients.Caller.SendAsync("ReceiveAiModels", models);
            }
        }

        public async Task AddAiModel(string model)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _settingsService.AddAiModel(model);
                var models = _settingsService.GetAiModels();
                await Clients.All.SendAsync("ReceiveAiModels", models);
            }
        }

        public async Task RemoveAiModel(string model)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _settingsService.RemoveAiModel(model);
                var models = _settingsService.GetAiModels();
                await Clients.All.SendAsync("ReceiveAiModels", models);
            }
        }

        public async Task SetDefaultAiModel(string model)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _settingsService.SetDefaultAiModel(model);
                await Clients.All.SendAsync("ReceiveDefaultAiModel", model);
            }
        }

        public async Task ReceiveAiHistory(string historyJson, int pid = -1)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                int targetPid = pid;
                if (targetPid <= 0) targetPid = _aiCliService.GetTargetPid();
                
                await Clients.All.SendAsync("ReceiveAiHistory", historyJson, targetPid);
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

        public async Task ExecuteMacro(object input)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "ExecuteMacro");
                if (input is JsonElement json)
                {
                    if (json.ValueKind == JsonValueKind.String)
                    {
                        await _macroService.ExecuteMacroAsync(json.GetString() ?? "");
                    }
                    else
                    {
                        var macro = json.Deserialize<MacroConfig>();
                        if (macro != null) await _macroService.ExecuteMacroAsync(macro);
                    }
                }
                else if (input is string script)
                {
                    await _macroService.ExecuteMacroAsync(script);
                }
            }
        }

        public List<MacroConfig> GetMacros()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                return _settingsService.Settings.Macros;
            }
            return new List<MacroConfig>();
        }

        public void SaveMacro(MacroConfig macro)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                var existing = _settingsService.Settings.Macros.FirstOrDefault(m => m.Id == macro.Id);
                if (existing != null)
                {
                    _settingsService.UpdateMacro(macro);
                }
                else
                {
                    _settingsService.AddMacro(macro);
                }
            }
        }

        public void DeleteMacro(Guid id)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                _settingsService.RemoveMacro(id);
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

        private void EnsureAuthenticated()
        {
            if (!Context.Items.TryGetValue("IsAuthenticated", out var authenticated) ||
                authenticated is not true)
            {
                throw new HubException("Unauthorized");
            }
        }

        private static Guid ParseReminderId(string reminderId)
        {
            if (!Guid.TryParse(reminderId, out var id))
                throw new HubException("Invalid reminder ID.");
            return id;
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


