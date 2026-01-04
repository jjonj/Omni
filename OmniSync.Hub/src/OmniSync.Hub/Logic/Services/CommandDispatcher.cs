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
        private readonly IHostApplicationLifetime _appLifetime;
        private readonly Dictionary<string, Action<JsonElement>> _commandMap;

        public event EventHandler<string>? AddCleanupPatternRequested;

        public CommandDispatcher(InputService inputService, FileService fileService, AudioService audioService, ProcessService processService, ShutdownService shutdownService, HubSettingsService settingsService, PcgPersistentService pcgService, IHostApplicationLifetime appLifetime) // Add AudioService and ProcessService to constructor
        {
            _inputService = inputService;
            _fileService = fileService;
            _audioService = audioService; // Assign AudioService
            _processService = processService; // Assign ProcessService
            _shutdownService = shutdownService;
            _settingsService = settingsService;
            _pcgService = pcgService;
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
                { "APPEND_NOTE", payload => _fileService.AppendToFile(payload.GetProperty("filename").GetString(), payload.GetProperty("content").GetString()) },
                { "SAVE_FILE", payload => _fileService.WriteBrowseFile(payload.GetProperty("Path").GetString(), payload.GetProperty("Content").GetString()) },
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
                { "SCHEDULE_SHUTDOWN", payload => _shutdownService.ScheduleShutdown(payload.GetProperty("Minutes").GetInt32()) },
                { "ADDCLEANUPPATTERN", payload => AddCleanupPatternRequested?.Invoke(this, payload.GetString() ?? "") },
                { "HUB_EXIT", payload => _appLifetime.StopApplication() },
                { "PCG_SAVE_STATE", payload => _pcgService.SaveObjectState(
                    payload.GetProperty("WorldId").GetString() ?? "default",
                    payload.GetProperty("X").GetSingle(),
                    payload.GetProperty("Y").GetSingle(),
                    payload.GetProperty("Data").GetString() ?? "",
                    payload.TryGetProperty("IsExclusion", out var excl) && excl.GetBoolean()
                ) }
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
                            Console.WriteLine($"Unknown command: {command}");
                        }
                    }
                }
            }
