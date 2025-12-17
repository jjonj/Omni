using System;
using System.Collections.Generic;
using System.Text.Json;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring; // Add this using directive

namespace OmniSync.Hub.Logic.Services
{
                    public class CommandDispatcher
                {
                    private readonly InputService _inputService;
                    private readonly FileService _fileService;
                    private readonly HubMonitorService _hubMonitorService;
                    private readonly AudioService _audioService; // Inject AudioService
                    private readonly Dictionary<string, Action<JsonElement>> _commandMap;
            
                    public CommandDispatcher(InputService inputService, FileService fileService, HubMonitorService hubMonitorService, AudioService audioService) // Add AudioService to constructor
                    {
                        _inputService = inputService;
                        _fileService = fileService;
                        _hubMonitorService = hubMonitorService;
                        _audioService = audioService; // Assign AudioService
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
                                        // Note: GET_NOTE and other commands that return data are handled directly in RpcApiHub
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
