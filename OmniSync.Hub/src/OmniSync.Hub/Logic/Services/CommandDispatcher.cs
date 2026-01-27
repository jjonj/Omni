using System;
using System.Collections.Generic;
using System.Text.Json;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using Microsoft.Extensions.Hosting;

namespace OmniSync.Hub.Logic.Services
{
    public class CommandDispatcher
    {
        private readonly InputService _inputService;
        private readonly FileService _fileService;
        private readonly AudioService _audioService; // Inject AudioService
        private readonly ProcessService _processService; // Inject ProcessService
        private readonly ShutdownService _shutdownService;
        private readonly HubSettingsService _settingsService;
        private readonly PcgPersistentService _pcgService;
        private readonly NodeRedService _nodeRedService;
        private readonly ProjectLauncherService _projectLauncherService;
        private readonly ResourceOpenerService _resourceOpenerService;
        private readonly IHostApplicationLifetime _appLifetime;
        private readonly Dictionary<string, Action<JsonElement>> _commandMap;

        public event EventHandler<string>? AddCleanupPatternRequested;
        public event EventHandler<(string Command, JsonElement Payload)>? ExternalCommandDispatched;

        public CommandDispatcher(InputService inputService, FileService fileService, AudioService audioService, ProcessService processService, ShutdownService shutdownService, HubSettingsService settingsService, PcgPersistentService pcgService, NodeRedService nodeRedService, ProjectLauncherService projectLauncherService, ResourceOpenerService resourceOpenerService, IHostApplicationLifetime appLifetime)
        {
            _inputService = inputService;
            _fileService = fileService;
            _audioService = audioService;
            _processService = processService;
            _shutdownService = shutdownService;
            _settingsService = settingsService;
            _pcgService = pcgService;
            _nodeRedService = nodeRedService;
            _projectLauncherService = projectLauncherService;
            _resourceOpenerService = resourceOpenerService;
            _appLifetime = appLifetime;
            _commandMap = new Dictionary<string, Action<JsonElement>>
            {
                { "LEFT_CLICK", payload => _inputService.LeftClick() },
                { "RIGHT_CLICK", payload => _inputService.RightClick() },
                { "MOUSE_MOVE", payload => _inputService.MoveMouse(payload.GetProperty("X").GetInt32(), payload.GetProperty("Y").GetInt32()) },
                { "MOUSE_SCROLL", payload => _inputService.MouseScroll(payload.GetProperty("Delta").GetInt32()) },
                { "MOUSE_CLICK_DOWN", payload => _inputService.MouseDown(payload.GetProperty("Button").GetString() ?? "Left") },
                { "MOUSE_CLICK_UP", payload => _inputService.MouseUp(payload.GetProperty("Button").GetString() ?? "Left") },
                { "INPUT_KEY_PRESS", payload => _inputService.SendKeyPress(payload.GetProperty("KeyCode").GetUInt16()) },
                { "INPUT_KEY_DOWN", payload => _inputService.KeyDown(payload.GetProperty("KeyCode").GetUInt16()) },
                { "INPUT_KEY_UP", payload => _inputService.KeyUp(payload.GetProperty("KeyCode").GetUInt16()) },
                { "INPUT_TEXT", payload => _inputService.SendText(payload.GetProperty("Text").GetString()) },
                { "SEND_KEYS", payload => _inputService.SendKeys(payload.GetProperty("Keys").GetString()) },
                { "VOLUME_CONTROL", payload => _inputService.SendVolumeKey(payload.GetProperty("KeyCode").GetUInt16()) },
                { "SET_VOLUME", payload => _audioService.SetMasterVolume(payload.GetProperty("VolumePercentage").GetSingle()) },
                { "TOGGLE_MUTE", payload => _audioService.ToggleMute() },
                { "LAUNCH_PROJECT", payload => {
                    if (payload.TryGetProperty("Id", out var idProp)) {
                        if (Guid.TryParse(idProp.GetString(), out var projectId)) {
                            var project = _settingsService.Settings.Projects.FirstOrDefault(p => p.Id == projectId);
                            if (project != null) {
                                _ = _projectLauncherService.LaunchProject(project);
                            }
                        }
                    }
                }},
                { "APPEND_NOTE", payload => _fileService.AppendToFile(payload.GetProperty("filename").GetString(), payload.GetProperty("content").GetString()) },
                { "SAVE_FILE", payload => _fileService.WriteBrowseFile(payload.GetProperty("Path").GetString(), payload.GetProperty("Content").GetString()) },
                { "COPY_FILE", payload => _fileService.CopyEntry(payload.GetProperty("Source").GetString(), payload.GetProperty("Dest").GetString()) },
                { "MOVE_FILE", payload => _fileService.MoveEntry(payload.GetProperty("Source").GetString(), payload.GetProperty("Dest").GetString()) },
                { "OPEN_ON_PC", payload => {
                    string? path = null;
                    if (payload.TryGetProperty("Exe", out var exeProp)) {
                        var exeKey = exeProp.GetString();
                        if (!string.IsNullOrEmpty(exeKey)) {
                            path = _settingsService.GetPath(exeKey);
                            if (string.IsNullOrEmpty(path)) {
                                Console.WriteLine($"Warning: No path mapping found for Exe key: {exeKey}");
                            }
                        }
                    } else if (payload.TryGetProperty("Path", out var pathProp)) {
                        path = pathProp.GetString();
                    }

                    if (!string.IsNullOrEmpty(path)) {
                        try {
                            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = true });
                        } catch (Exception ex) {
                            Console.WriteLine($"Error opening on PC: {ex.Message}");
                        }
                    }
                }},
                { "OPEN_RESOURCE", payload => {
                    string? path = null;
                    if (payload.TryGetProperty("Path", out var pathProp)) {
                        path = pathProp.GetString();
                    }
                    
                    int? lineNumber = null;
                    if (payload.TryGetProperty("LineNumber", out var lineProp)) {
                        if (lineProp.ValueKind == JsonValueKind.Number) {
                            lineNumber = lineProp.GetInt32();
                        } else if (lineProp.ValueKind == JsonValueKind.String && int.TryParse(lineProp.GetString(), out var line)) {
                            lineNumber = line;
                        }
                    }

                    if (!string.IsNullOrEmpty(path)) {
                        _ = _resourceOpenerService.OpenResource(path, lineNumber);
                    }
                }},
                { "SCHEDULE_SHUTDOWN", payload => _shutdownService.ScheduleShutdown(payload.GetProperty("Minutes").GetInt32()) },
                { "ADDCLEANUPPATTERN", payload => AddCleanupPatternRequested?.Invoke(this, payload.GetString() ?? "") },
                { "HUB_EXIT", payload => _appLifetime.StopApplication() },
                { "WIN_MINIMIZE", payload => _processService.WinMinimize(payload.GetProperty("Title").GetString() ?? "") },
                { "WIN_MAXIMIZE", payload => _processService.WinMaximize(payload.GetProperty("Title").GetString() ?? "") },
                { "WIN_HIDE", payload => _processService.WinHide(payload.GetProperty("Title").GetString() ?? "") },
                { "WAIT_WIN_ACTIVE", payload => _processService.WaitWinActive(payload.GetProperty("Title").GetString() ?? "", payload.GetProperty("TimeoutMs").GetInt32()) },
                { "MOUSE_MOVE_ABS", payload => _processService.MouseMoveAbs(payload.GetProperty("X").GetInt32(), payload.GetProperty("Y").GetInt32()) },
                { "MOUSE_CLICK_AT", payload => _processService.MouseClickAt(payload.GetProperty("Button").GetString() ?? "left", payload.GetProperty("X").GetInt32(), payload.GetProperty("Y").GetInt32()) },
                { "POWERSHELL", payload => {
                    var code = payload.GetProperty("Code").GetString();
                    if (!string.IsNullOrEmpty(code)) {
                        // Use RunPowerShell from ProcessService (need to make public or similar)
                        // For now let's just use a simple Process.Start if we can't access RunPowerShell
                        // Actually, I just added RunPowerShell to ProcessService, I should use it.
                        // I'll make a public version or just use the logic.
                        _processService.ExecuteCommand($"powershell -Command \"{code.Replace("\"", "\\\"")}\"");
                    }
                }},
                { "PCG_SAVE_STATE", payload => _pcgService.SaveObjectState(
                    payload.GetProperty("WorldId").GetString() ?? "default",
                    payload.GetProperty("X").GetSingle(),
                    payload.GetProperty("Y").GetSingle(),
                    payload.GetProperty("Data").GetString() ?? "",
                    payload.TryGetProperty("IsExclusion", out var excl) && excl.GetBoolean()
                ) },
                { "TRIGGER_NODERED", payload => {
                    var endpoint = payload.GetProperty("Endpoint").GetString();
                    if (!string.IsNullOrEmpty(endpoint)) {
                        object? data = null;
                        if (payload.TryGetProperty("Data", out var dataProp)) {
                            data = dataProp;
                        }
                        _ = _nodeRedService.TriggerFlowAsync(endpoint, data);
                    }
                }}
            };
        }
            
                    public void Dispatch(string command, JsonElement payload)
                    {
                        if (_commandMap.TryGetValue(command.ToUpper(), out var action))
                        {
                            action(payload);
                        }
                        else
                        {
                            ExternalCommandDispatched?.Invoke(this, (command, payload));
                        }
                    }

                    public void Dispatch(string command, object payload)
                    {
                        var json = JsonSerializer.Serialize(payload);
                        using var doc = JsonDocument.Parse(json);
                        Dispatch(command, doc.RootElement);
                    }

                    public IEnumerable<string> GetRegisteredCommands()
                    {
                        return _commandMap.Keys;
                    }
                }
            }
