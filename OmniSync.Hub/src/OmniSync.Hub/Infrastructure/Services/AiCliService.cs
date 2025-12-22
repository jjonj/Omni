using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Management;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class GeminiResponseEventArgs : EventArgs
    {
        public int Pid { get; set; }
        public string Text { get; set; } = string.Empty;
        public bool IsFinished { get; set; }
        public bool IsHistory { get; set; }
    }

    public class AiCliService : IDisposable
    {
        private readonly ILogger<AiCliService> _logger;
        private readonly ConcurrentDictionary<int, GeminiSession> _sessions = new();
        private int _targetPid = -1;
        private readonly SemaphoreSlim _sessionLock = new(1, 1);

        public event EventHandler<GeminiResponseEventArgs>? ResponseReceived;

        public AiCliService(ILogger<AiCliService> logger)
        {
            _logger = logger;
        }

        public async Task<List<int>> DiscoverSessionsAsync(int connectionTimeoutMs = 3000)
        {
            var pids = new List<int>();
            try
            {
                if (OperatingSystem.IsWindows())
                {
                    string query = "SELECT ProcessId, CommandLine FROM Win32_Process WHERE Name LIKE 'node%'";
                    using var searcher = new ManagementObjectSearcher(query);
                    using var collection = searcher.Get();

                    foreach (var process in collection)
                    {
                        var commandLine = process["CommandLine"]?.ToString();
                        var pidObj = process["ProcessId"];
                        if (commandLine != null && pidObj != null)
                        {
                            _logger.LogDebug($"Checking node process {pidObj}: {commandLine}");
                            if ((commandLine.Contains("bundle/gemini.js") || commandLine.Contains("dist/index.js") || (commandLine.Contains("gemini-cli") && commandLine.Contains("index.js")))
                                && !commandLine.Contains("@google")) // Exclude standard global install which lacks named pipe
                            {
                                _logger.LogInformation($"Found matching Gemini process: PID {pidObj}");
                                pids.Add(Convert.ToInt32(pidObj));
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error discovering node processes via WMI");
            }

            // Clean up stale sessions
            foreach (var pid in _sessions.Keys)
            {
                if (!pids.Contains(pid))
                {
                    if (_sessions.TryRemove(pid, out var session))
                    {
                        session.Dispose();
                    }
                }
            }

            // Ensure we have sessions for all PIDs found - Parallelized discovery
            var ensureTasks = pids.Where(p => !_sessions.ContainsKey(p))
                                  .Select(p => EnsureSessionAsync(p, connectionTimeoutMs));
            
            await Task.WhenAll(ensureTasks);

            var connectedPids = _sessions.Where(s => s.Value.IsConnected).Select(s => s.Key).ToList();

            if (_targetPid == -1 && connectedPids.Count > 0)
            {
                _targetPid = connectedPids[0];
            }

            return connectedPids;
        }

        public async Task<bool> LaunchSessionAsync(string? workspace = null)
        {
            try
            {
                // D:\SSDProjects\Omni\OmniSync.Hub\bin\Debug\net9.0-windows\OmniSync.Hub.exe
                // Go up 6 levels to get to D:\SSDProjects\Omni
                string rootPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", ".."));
                
                // Gemini is at D:\SSDProjects\Tools\gemini-cli (sibling to Omni)
                string geminiDir = Path.GetFullPath(Path.Combine(rootPath, "..", "Tools", "gemini-cli"));

                if (string.IsNullOrWhiteSpace(workspace))
                {
                    // Default workspace is D:\SSDProjects (parent of Omni)
                    workspace = Path.GetFullPath(Path.Combine(rootPath, ".."));
                }
                else
                {
                    workspace = Path.GetFullPath(workspace);
                }

                _logger.LogInformation($"AiCliService: Launching new Gemini CLI session.");
                _logger.LogInformation($"  Gemini Dir: {geminiDir}");
                _logger.LogInformation($"  Workspace: {workspace}");

                // Construct the command exactly as the working python script does:
                // cmd = f'title OMNI_GEMINI_INTERACTIVE && cd /d {gemini_dir} && node bundle/gemini.js --workspace {workspace}'
                // Note: NO QUOTES around the workspace path in the final command string to avoid node path resolution issues.
                string command = $"title OMNI_GEMINI_INTERACTIVE && cd /d \"{geminiDir}\" && node bundle/gemini.js --workspace {workspace}";

                var startInfo = new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = $"/K \"{command}\"", // /K to keep window open
                    UseShellExecute = true, // Needed for separate window
                    CreateNoWindow = false,
                    WindowStyle = ProcessWindowStyle.Normal
                };

                // Set environment variable for the new process
                // Note: When UseShellExecute is true, we can't set EnvironmentVariables directly in .NET Core/5+ easily without native P/Invoke or using a wrapper cmd.
                // However, we can inject it into the command string or use a temporary batch file?
                // Or simply set it in the current process before launch? No, that affects this process.
                // Actually, "start" command can execute a block with env vars if we are clever, or we can just set it in the command chain:
                // "set GEMINI_DEBUG_LOG_FILE=... && title ... && ..."
                
                string debugLog = Path.Combine(rootPath, "gemini_cli_debug.log");
                // Update command to include setting the env var
                string finalCommand = $"set GEMINI_DEBUG_LOG_FILE={debugLog} && {command}";
                
                startInfo.Arguments = $"/K \"{finalCommand}\"";

                Process.Start(startInfo);

                // Wait for the process and its pipe
                for (int i = 0; i < 15; i++)
                {
                    await Task.Delay(1000);
                    // Use longer timeout for the session we just launched
                    var connected = await DiscoverSessionsAsync(5000); 
                    if (connected.Count > 0) 
                    {
                        _logger.LogInformation($"AiCliService: Successfully connected to new session.");
                        return true;
                    }
                }

                return false;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error launching Gemini CLI session");
                return false;
            }
        }

        private async Task EnsureSessionAsync(int pid, int timeoutMs)
        {
            // First check if we already have it
            if (_sessions.ContainsKey(pid)) return;

            var session = new GeminiSession(pid, _logger, (p, text, finished, history) =>
            {
                ResponseReceived?.Invoke(this, new GeminiResponseEventArgs
                {
                    Pid = p,
                    Text = text,
                    IsFinished = finished,
                    IsHistory = history
                });
            });

            // Connect outside the lock to allow parallel connection attempts
            if (await session.ConnectAsync(timeoutMs))
            {
                await _sessionLock.WaitAsync();
                try
                {
                    // Double check inside lock
                    if (!_sessions.ContainsKey(pid))
                    {
                        _sessions[pid] = session;
                        _logger.LogInformation($"Connected to Gemini session PID {pid}");
                    }
                    else
                    {
                        session.Dispose();
                    }
                }
                finally
                {
                    _sessionLock.Release();
                }
            }
            else
            {
                session.Dispose();
            }
        }

        public async Task SetTargetPidAsync(int pid)
        {
            if (!_sessions.ContainsKey(pid))
            {
                await DiscoverSessionsAsync(2000);
            }

            if (_sessions.ContainsKey(pid))
            {
                _targetPid = pid;
            }
        }

        public async Task<bool> SendPromptAsync(string text, int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;

            // If target is invalid, try to discover existing
            if (target == -1 || !_sessions.TryGetValue(target, out var targetSession) || !targetSession.IsConnected)
            {
                var connected = await DiscoverSessionsAsync(2000);
                if (connected.Count > 0)
                {
                    target = connected[0];
                    if (_targetPid == -1) _targetPid = target;
                }
                else
                {
                    // AUTO-LAUNCH if none connected
                    _logger.LogInformation("No active AI sessions. Auto-launching Gemini CLI...");
                    if (await LaunchSessionAsync())
                    {
                        // Discovery happened inside LaunchSessionAsync, so check _sessions again
                        var connectedAfterLaunch = _sessions.Keys.Where(k => _sessions[k].IsConnected).ToList();
                        if (connectedAfterLaunch.Count > 0)
                        {
                            target = connectedAfterLaunch[0];
                            if (_targetPid == -1) _targetPid = target;
                        }
                        else return false;
                    }
                    else return false;
                }
            }

            await _sessionLock.WaitAsync();
            try
            {
                if (_sessions.TryGetValue(target, out var session))
                {
                    return await session.SendPromptAsync(text);
                }
                return false;
            }
            finally
            {
                _sessionLock.Release();
            }
        }

        public async Task GetHistoryAsync(int pid)
        {
            if (_sessions.TryGetValue(pid, out var session))
            {
                await session.RequestHistoryAsync();
            }
        }

        public async Task<bool> StopSessionAsync(int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (_sessions.TryGetValue(target, out var session))
            {
                session.Dispose();
                _sessions.TryRemove(target, out _);
                
                try
                {
                    var process = Process.GetProcessById(target);
                    if (!process.HasExited)
                    {
                        process.Kill(true);
                        _logger.LogInformation($"Killed AI process {target}");
                    }
                }
                catch (Exception ex)
                {
                    _logger.LogWarning($"Could not kill process {target}: {ex.Message}");
                }

                if (_targetPid == target) _targetPid = -1;
                return true;
            }
            return false;
        }

        public void Dispose()
        {
            foreach (var session in _sessions.Values)
            {
                session.Dispose();
            }
            _sessions.Clear();
            _sessionLock.Dispose();
        }
    }

    internal class GeminiSession : IDisposable
    {
        private readonly int _pid;
        private readonly ILogger _logger;
        private readonly Action<int, string, bool, bool> _onResponse;
        private NamedPipeClientStream? _pipeClient;
        private StreamWriter? _writer;
        private CancellationTokenSource? _cts;
        private readonly SemaphoreSlim _writeLock = new(1, 1);
        private readonly StringBuilder _currentResponse = new();

        public bool IsConnected => _pipeClient?.IsConnected ?? false;

        public GeminiSession(int pid, ILogger logger, Action<int, string, bool, bool> onResponse)
        {
            _pid = pid;
            _logger = logger;
            _onResponse = onResponse;
        }

        public async Task<bool> ConnectAsync(int timeoutMs)
        {
            try
            {
                _pipeClient = new NamedPipeClientStream(".", $"gemini-cli-{_pid}", PipeDirection.InOut, PipeOptions.Asynchronous);
                await _pipeClient.ConnectAsync(timeoutMs);

                _writer = new StreamWriter(_pipeClient) { AutoFlush = true };
                _cts = new CancellationTokenSource();
                _ = Task.Run(() => ReadLoopAsync(_cts.Token));

                return true;
            }
            catch (Exception)
            {
                _logger.LogWarning($"Could not connect to pipe for PID {_pid} within {timeoutMs}ms");
                return false;
            }
        }

        public async Task<bool> SendPromptAsync(string text)
        {
            _currentResponse.Clear();
            return await SendCommandAsync("prompt", text);
        }

        public async Task RequestHistoryAsync()
        {
            await SendCommandAsync("getHistory", null);
        }

        private async Task<bool> SendCommandAsync(string command, string? text)
        {
            if (!IsConnected || _writer == null) 
            {
                _logger.LogWarning($"Cannot send command '{command}': Not connected to PID {_pid}");
                return false;
            }

            await _writeLock.WaitAsync();
            try
            {
                var payload = JsonSerializer.Serialize(new { command, text });
                _logger.LogInformation($"Sending to PID {_pid}: {payload}");
                await _writer.WriteLineAsync(payload);
                await _writer.FlushAsync();
                return true;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error sending command to PID {_pid}");
                return false;
            }
            finally
            {
                _writeLock.Release();
            }
        }

        private async Task ReadLoopAsync(CancellationToken token)
        {
            if (_pipeClient == null) return;

            _logger.LogInformation($"Starting read loop for PID {_pid}");
            using var reader = new StreamReader(_pipeClient, Encoding.UTF8, false, 1024, leaveOpen: true);
            try
            {
                while (!token.IsCancellationRequested && IsConnected)
                {
                    var line = await reader.ReadLineAsync(token);
                    if (line == null) 
                    {
                        break;
                    }

                    _logger.LogInformation($"Received from PID {_pid}: {line}");
                    try
                    {
                        var msg = JsonDocument.Parse(line);
                        if (msg.RootElement.TryGetProperty("type", out var type) && type.GetString() == "response")
                        {
                            var text = msg.RootElement.GetProperty("text").GetString();
                            if (text == null) continue;

                            if (text.Contains("[HISTORY_START]"))
                            {
                                int startIdx = text.IndexOf("[HISTORY_START]") + "[HISTORY_START]".Length;
                                int endIdx = text.IndexOf("[HISTORY_END]", startIdx);
                                if (endIdx != -1)
                                {
                                    var historyJson = text.Substring(startIdx, endIdx - startIdx);
                                    _onResponse(_pid, historyJson, true, true);
                                }
                            }
                            else if (text == "[TURN_FINISHED]")
                            {
                                _onResponse(_pid, string.Empty, true, false);
                            }
                            else if (text == "[Command Handled]")
                            {
                                // No-op
                            }
                            else
                            {
                                // Stream the text immediately
                                _onResponse(_pid, text, false, false);
                            }
                        }
                    }
                    catch (JsonException) { }
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Read loop error for PID {_pid}");
            }
            finally
            {
                _logger.LogInformation($"Read loop finished for PID {_pid}");
            }
        }

        public void Dispose()
        {
            _cts?.Cancel();
            _writer?.Dispose();
            _pipeClient?.Dispose();
            _cts?.Dispose();
            _writeLock.Dispose();
        }
    }
}