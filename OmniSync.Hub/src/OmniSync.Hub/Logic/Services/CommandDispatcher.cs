using System;
using System.Collections.Generic;
using System.Text.Json;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring; // Add this using directive
using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Presentation.Hubs;

namespace OmniSync.Hub.Logic.Services
{
                    public class CommandDispatcher
                {
                    private readonly InputService _inputService;
                    private readonly FileService _fileService;
                    private readonly HubMonitorService _hubMonitorService;
                    private readonly AudioService _audioService; // Inject AudioService
                    private readonly ProcessService _processService; // Inject ProcessService
                    private readonly ShutdownService _shutdownService;
                    private readonly IHubContext<RpcApiHub> _hubContext;
                    private readonly Dictionary<string, Action<JsonElement>> _commandMap;
            
                    public CommandDispatcher(InputService inputService, FileService fileService, HubMonitorService hubMonitorService, AudioService audioService, ProcessService processService, ShutdownService shutdownService, IHubContext<RpcApiHub> hubContext) // Add AudioService and ProcessService to constructor
                    {
                        _inputService = inputService;
                        _fileService = fileService;
                        _hubMonitorService = hubMonitorService;
                        _audioService = audioService; // Assign AudioService
                        _processService = processService; // Assign ProcessService
                        _shutdownService = shutdownService;
                        _hubContext = hubContext;
                                    _commandMap = new Dictionary<string, Action<JsonElement>>
                                    {
                                        { "LEFT_CLICK", payload => _inputService.LeftClick() },
                                        { "RIGHT_CLICK", payload => _inputService.RightClick() },
                                        { "MOUSE_MOVE", payload => _inputService.MoveMouse(payload.GetProperty("X").GetInt32(), payload.GetProperty("Y").GetInt32()) },
                                        { "INPUT_KEY_PRESS", payload => _inputService.SendKeyPress(payload.GetProperty("KeyCode").GetUInt16()) },
                                        { "INPUT_KEY_DOWN", payload => _inputService.KeyDown(payload.GetProperty("KeyCode").GetUInt16()) },
                                        { "INPUT_KEY_UP", payload => _inputService.KeyUp(payload.GetProperty("KeyCode").GetUInt16()) },
                                        { "INPUT_TEXT", payload => _inputService.SendText(payload.GetProperty("Text").GetString()) },
                                        { "VOLUME_CONTROL", payload => _inputService.SendVolumeKey(payload.GetProperty("KeyCode").GetUInt16()) },
                                        { "SET_VOLUME", payload => _audioService.SetMasterVolume(payload.GetProperty("VolumePercentage").GetSingle()) },
                                        { "TOGGLE_MUTE", payload => _audioService.ToggleMute() },
                                        { "APPEND_NOTE", payload => _fileService.AppendToFile(payload.GetProperty("filename").GetString(), payload.GetProperty("content").GetString()) },
                                        { "SAVE_FILE", payload => _fileService.WriteBrowseFile(payload.GetProperty("Path").GetString(), payload.GetProperty("Content").GetString()) },
                                        { "OPEN_ON_PC", payload => {
                                            var path = payload.GetProperty("Path").GetString();
                                            if (!string.IsNullOrEmpty(path)) {
                                                try {
                                                    System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = true });
                                                } catch (Exception ex) {
                                                    Console.WriteLine($"Error opening file on PC: {ex.Message}");
                                                }
                                            }
                                        }},
            { "SCHEDULE_SHUTDOWN", payload => _shutdownService.ScheduleShutdown(payload.GetProperty("Minutes").GetInt32()) },
            { "ADDCLEANUPPATTERN", payload => _hubContext.Clients.All.SendAsync("ReceiveBrowserCommand", "AddCleanupPattern", payload.GetString(), false) }
        };                    }
            
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
