using System;
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
        public string Text { get; set; }
    }

    public class AiCliService
    {
        private readonly ILogger<AiCliService> _logger;
        private readonly Dictionary<int, GeminiSession> _sessions = new();
        private int _targetPid = -1;
        private readonly SemaphoreSlim _sessionLock = new(1, 1);

        public event EventHandler<GeminiResponseEventArgs>? ResponseReceived;

        public AiCliService(ILogger<AiCliService> logger)
        {
            _logger = logger;
        }

        public async Task<List<int>> DiscoverSessionsAsync()
        {
            var pids = new List<int>();
            try
            {
                using var searcher = new ManagementObjectSearcher("SELECT ProcessId, CommandLine FROM Win32_Process WHERE Name = 'node.exe'");
                foreach (ManagementObject obj in searcher.Get().Cast<ManagementObject>())
                {
                    var pid = Convert.ToInt32(obj["ProcessId"]);
                    var commandLine = obj["CommandLine"]?.ToString() ?? "";

                    if (commandLine.Contains("bundle/gemini.js") || commandLine.Contains("dist/index.js"))
                    {
                        pids.Add(pid);
                        await EnsureSessionAsync(pid);
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error discovering Gemini sessions");
            }

            // Cleanup stale sessions
            await _sessionLock.WaitAsync();
            try
            {
                var stalePids = _sessions.Keys.Where(p => !pids.Contains(p)).ToList();
                foreach (var stalePid in stalePids)
                {
                    _sessions[stalePid].Dispose();
                    _sessions.Remove(stalePid);
                }
            }
            finally
            {
                _sessionLock.Release();
            }

            if (_targetPid == -1 && pids.Count > 0)
            {
                _targetPid = pids[0];
            }

            return pids;
        }

        private async Task EnsureSessionAsync(int pid)
        {
            await _sessionLock.WaitAsync();
            try
            {
                if (!_sessions.ContainsKey(pid))
                {
                    var session = new GeminiSession(pid, _logger, (p, text) => 
                    {
                        ResponseReceived?.Invoke(this, new GeminiResponseEventArgs { Pid = p, Text = text });
                    });
                    
                    if (await session.ConnectAsync())
                    {
                        _sessions[pid] = session;
                        _logger.LogInformation($"Connected to Gemini session {pid}");
                    }
                    else
                    {
                        session.Dispose();
                    }
                }
            }
            finally
            {
                _sessionLock.Release();
            }
        }

        public void SetTargetPid(int pid)
        {
            _targetPid = pid;
        }

        public async Task<bool> SendPromptAsync(string text, int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target == -1)
            {
                // Try to discover sessions if none exist
                var sessions = await DiscoverSessionsAsync();
                if (sessions.Count > 0)
                {
                    target = sessions[0];
                }
                else
                {
                    return false;
                }
            }

            await _sessionLock.WaitAsync();
            try
            {
                if (_sessions.TryGetValue(target, out var session))
                {
                    return await session.SendPromptAsync(text);
                }
            }
            finally
            {
                _sessionLock.Release();
            }

            return false;
        }

        public async Task<bool> GetHistoryAsync(int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target == -1) return false;

            await _sessionLock.WaitAsync();
            try
            {
                if (_sessions.TryGetValue(target, out var session))
                {
                    await session.RequestHistoryAsync();
                    return true;
                }
            }
            finally
            {
                _sessionLock.Release();
            }

            return false;
        }
    }

    internal class GeminiSession : IDisposable
    {
        private readonly int _pid;
        private readonly ILogger _logger;
        private readonly Action<int, string> _onResponse;
        private NamedPipeClientStream? _pipeClient;
        private StreamWriter? _writer;
        private CancellationTokenSource? _cts;
        private Task? _readTask;
        private readonly SemaphoreSlim _writeLock = new(1, 1);

        public bool IsConnected => _pipeClient?.IsConnected ?? false;

        public GeminiSession(int pid, ILogger logger, Action<int, string> onResponse)
        {
            _pid = pid;
            _logger = logger;
            _onResponse = onResponse;
        }

        public async Task<bool> ConnectAsync()
        {
            try
            {
                _pipeClient = new NamedPipeClientStream(".", $"gemini-cli-{_pid}", PipeDirection.InOut, PipeOptions.Asynchronous);
                await _pipeClient.ConnectAsync(5000);

                _writer = new StreamWriter(_pipeClient) { AutoFlush = true };
                _cts = new CancellationTokenSource();
                _readTask = Task.Run(() => ReadLoopAsync(_cts.Token));

                return true;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Failed to connect to pipe for PID {_pid}");
                return false;
            }
        }

        public async Task<bool> SendPromptAsync(string text)
        {
            return await SendCommandAsync("prompt", text);
        }

        public async Task RequestHistoryAsync()
        {
            await SendCommandAsync("getHistory", null);
        }

        private async Task<bool> SendCommandAsync(string command, string? text)
        {
            if (!IsConnected || _writer == null) return false;

            await _writeLock.WaitAsync();
            try
            {
                var payload = JsonSerializer.Serialize(new { command, text });
                await _writer.WriteLineAsync(payload);
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
            
            using var reader = new StreamReader(_pipeClient, Encoding.UTF8, false, 1024, leaveOpen: true);
            try
            {
                while (!token.IsCancellationRequested && IsConnected)
                {
                    var line = await reader.ReadLineAsync(token);
                    if (line == null) break;

                    try
                    {
                        var msg = JsonDocument.Parse(line);
                        if (msg.RootElement.TryGetProperty("type", out var type) && type.GetString() == "response")
                        {
                            var text = msg.RootElement.GetProperty("text").GetString();
                            if (text != null)
                            {
                                _onResponse(_pid, text);
                            }
                        }
                    }
                    catch (JsonException)
                    {
                        // Ignore malformed lines
                    }
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
