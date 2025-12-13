using System;
using System.Collections.Generic;
using System.Text.Json;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic.Services
{
                    public class CommandDispatcher
                {
                    private readonly InputService _inputService;
                    private readonly FileService _fileService;
                    private readonly Dictionary<string, Action<JsonElement>> _commandMap;
            
                    public CommandDispatcher(InputService inputService, FileService fileService)
                    {
                        _inputService = inputService;
                        _fileService = fileService;
                                    _commandMap = new Dictionary<string, Action<JsonElement>>
                                    {
                                        { "MOUSE_MOVE", payload => _inputService.MoveMouse(payload.GetProperty("x").GetInt32(), payload.GetProperty("y").GetInt32()) },
                                        { "INPUT_KEY_PRESS", payload => _inputService.SendKeyPress(payload.GetProperty("keyCode").GetUInt16()) }, // Renamed from KEY_PRESS
                                        { "INPUT_KEY_DOWN", payload => _inputService.KeyDown(payload.GetProperty("keyCode").GetUInt16()) },
                                        { "INPUT_KEY_UP", payload => _inputService.KeyUp(payload.GetProperty("keyCode").GetUInt16()) },
                                        { "INPUT_TEXT", payload => _inputService.SendText(payload.GetProperty("text").GetString()) },
                                        { "APPEND_NOTE", payload => _fileService.AppendToFile(payload.GetProperty("filename").GetString(), payload.GetProperty("content").GetString()) },
                                        // Note: GET_NOTE and other commands that return data are handled directly in RpcApiHub
                                    };                    }
            
                    public void Dispatch(string command, JsonElement payload)
                    {
                        if (_commandMap.TryGetValue(command, out var action))
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
