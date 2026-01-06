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

    public class AiSessionInfo
    {
        [System.Text.Json.Serialization.JsonPropertyName("pid")]
        public int Pid { get; set; }
        
        [System.Text.Json.Serialization.JsonPropertyName("name")]
        public string Name { get; set; } = string.Empty;
        
        [System.Text.Json.Serialization.JsonPropertyName("startTime")]
        public DateTime StartTime { get; set; }
    }

    public class AiCliService : IDisposable
    {
        private readonly ILogger<AiCliService> _logger;
        private readonly HubSettingsService _settingsService;
        private readonly ConcurrentDictionary<int, GeminiSession> _sessions = new();
        private readonly ConcurrentDictionary<int, string> _sessionNames = new();
        private readonly ConcurrentDictionary<int, (DateTime LastAttempt, int FailCount)> _failedPids = new();
        private DateTime _lastWmiDiscovery = DateTime.MinValue;
        private List<int> _cachedWmiPids = new();
        private int _targetPid = -1;
        private readonly SemaphoreSlim _sessionLock = new(1, 1);

        public event EventHandler<GeminiResponseEventArgs>? ResponseReceived;

        public AiCliService(ILogger<AiCliService> logger, HubSettingsService settingsService)
        {
            _logger = logger;
            _settingsService = settingsService;
        }

        public Task SetSessionNameAsync(int pid, string name)
        {
            _sessionNames[pid] = name;
            _logger.LogInformation($"Renamed session PID {pid} to '{name}'");
            
            if (_sessions.TryGetValue(pid, out var session))
            {
                var key = $"{session.StartTime.Ticks}_{pid}";
                _settingsService.SetAiSessionName(key, name);
            }

            return Task.CompletedTask;
        }

        public void TryAutoRenameSession(int pid, string firstMessage)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target == -1 || string.IsNullOrWhiteSpace(firstMessage)) return;
            
            // Check in-memory first
            if (_sessionNames.ContainsKey(target)) return; 

            // Check settings before renaming to ensure true persistence
            if (_sessions.TryGetValue(target, out var session))
            {
                var key = $"{session.StartTime.Ticks}_{target}";
                var savedName = _settingsService.GetAiSessionName(key);
                if (savedName != null)
                {
                    _sessionNames[target] = savedName;
                    return;
                }
            }

            string name = GenerateName(firstMessage);
            if (!string.IsNullOrWhiteSpace(name))
            {
                _sessionNames[target] = name;
                _logger.LogInformation($"Auto-renamed session PID {target} to '{name}' based on first message.");
                
                if (_sessions.TryGetValue(target, out var session2))
                {
                    var key = $"{session2.StartTime.Ticks}_{target}";
                    _settingsService.SetAiSessionName(key, name);
                }
            }
        }

        private string GenerateName(string message)
        {
            // Words to skip ONLY if they appear at the beginning of the sentence
            var prefixWords = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                "please", "can", "you", "i", "want", "to", "could", "would", "should", "tell", "give", "show", 
                "help", "with", "write", "create", "make", "find", "search", "check", "analyze", "suggest", 
                "summarize", "explain", "how", "what", "why", "when", "where", "who", "is", "are", "am", 
                "do", "does", "did", "for", "of", "in", "on", "at", "by", "this", "that", "will", "my", "your", 
                "it", "its", "from", "now", "here", "there", "me", "a", "an", "the"
            };

            var words = message.Split(new[] { ' ', '\t', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries);
            
            // Find the first word that isn't a prefix word
            int startIndex = 0;
            while (startIndex < words.Length && prefixWords.Contains(words[startIndex].Trim(new[] { '.', ',', '?', '!', '"', '\'', ':', ';' })))
            {
                startIndex++;
            }

            // If we skipped everything, just take the first few words
            if (startIndex >= words.Length) startIndex = 0;

            string baseName = string.Join(" ", words.Skip(startIndex).Take(3));
            
            if (baseName.Length > 12)
            {
                return baseName.Substring(0, 12).Trim() + "..";
            }
            return baseName;
        }

        public string GetSessionName(int pid)
        {
            return _sessionNames.TryGetValue(pid, out var name) ? name : $"Session {pid}";
        }

        public IDictionary<int, string> GetSessionsWithNames()
        {
            var result = new Dictionary<int, string>();
            foreach (var pid in _sessions.Keys)
            {
                if (_sessions.TryGetValue(pid, out var session) && session.IsConnected)
                {
                    result[pid] = GetSessionName(pid);
                }
            }
            return result;
        }

        public List<AiSessionInfo> GetActiveSessions()
        {
            return _sessions.Values
                .Where(s => s.IsConnected)
                .OrderBy(s => s.StartTime)
                .Select(s => new AiSessionInfo
                {
                    Pid = s.Pid,
                    Name = GetSessionName(s.Pid),
                    StartTime = s.StartTime
                })
                .ToList();
        }

        public async Task<List<int>> DiscoverSessionsAsync(int connectionTimeoutMs = 1000, int startupTimeout = 5000)
        {
            _logger.LogInformation($"[AiCliService] DiscoverSessionsAsync started (timeout: {connectionTimeoutMs}ms, startup: {startupTimeout}ms)");
            var sw = Stopwatch.StartNew();
            var pids = new List<int>();
            var now = DateTime.Now;

            // Clean up old failed PIDs (older than 5 minutes)
            foreach (var kp in _failedPids.Where(kvp => (now - kvp.Value.LastAttempt).TotalMinutes > 5).ToList())
            {
                _failedPids.TryRemove(kp.Key, out _);
            }

            // Cache WMI for 5 seconds to avoid spamming slow queries
            if ((now - _lastWmiDiscovery).TotalSeconds < 5 && _cachedWmiPids.Any())
            {
                _logger.LogDebug("[AiCliService] Using cached WMI discovery results");
                pids = _cachedWmiPids.ToList();
            }
            else
            {
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
                                int foundPid = Convert.ToInt32(pidObj);

                                // Skip if it's already a connected session
                                if (_sessions.TryGetValue(foundPid, out var existing) && existing.IsConnected)
                                {
                                    pids.Add(foundPid);
                                    continue;
                                }

                                // Skip if failed too many times
                                if (_failedPids.TryGetValue(foundPid, out var failInfo))
                                {
                                    if (failInfo.FailCount >= 3)
                                    {
                                        if ((now - failInfo.LastAttempt).TotalMinutes < 1) continue;
                                    }
                                    if ((now - failInfo.LastAttempt).TotalSeconds < 10) continue;
                                }

                                // Refined matching:
                                bool isGemini = (commandLine.Contains("bundle/gemini.js") || 
                                                 commandLine.Contains("gemini-cli") || 
                                                 commandLine.Contains("OMNI_GEMINI")) && 
                                                !commandLine.Contains("@google");

                                if (isGemini)
                                {
                                    _logger.LogInformation($"[AiCliService] Found potential Gemini process: PID {foundPid}");
                                    pids.Add(foundPid);
                                }
                            }
                        }
                        _lastWmiDiscovery = now;
                        _cachedWmiPids = pids.ToList();
                    }
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "[AiCliService] Error discovering node processes via WMI");
                }
            }

            _logger.LogDebug($"[AiCliService] Discovery phase took {sw.ElapsedMilliseconds}ms. Found {pids.Count} potential PIDs.");

            // Clean up stale sessions
            foreach (var pid in _sessions.Keys)
            {
                if (!pids.Contains(pid))
                {
                    bool isDead = true;
                    try
                    {
                        var proc = Process.GetProcessById(pid);
                        if (!proc.HasExited) isDead = false;
                    }
                    catch { }

                    if (isDead)
                    {
                        if (_sessions.TryRemove(pid, out var session))
                        {
                            session.Dispose();
                            _logger.LogInformation($"[AiCliService] Removed stale session PID {pid}");
                        }
                    }
                }
            }

            // Ensure we have sessions for all PIDs found
            var pidsToConnect = pids.Where(p => !_sessions.ContainsKey(p) || !_sessions[p].IsConnected).ToList();
            if (pidsToConnect.Any())
            {
                // Use a very short timeout for unknown processes to avoid blocking Hub.
                int effectiveTimeout = Math.Min(connectionTimeoutMs, 300); 
                _logger.LogInformation($"[AiCliService] Attempting to connect to {pidsToConnect.Count} potential sessions: {string.Join(", ", pidsToConnect)} (timeout: {effectiveTimeout}ms)");
                
                var ensureTasks = pidsToConnect.Select(p => EnsureSessionAsync(p, effectiveTimeout));
                await Task.WhenAll(ensureTasks);
            }
            
            var connectedPids = _sessions.Where(s => s.Value.IsConnected).Select(s => s.Key).ToList();
            _logger.LogInformation($"[AiCliService] DiscoverSessionsAsync finished in {sw.ElapsedMilliseconds}ms. {connectedPids.Count} connected sessions: {string.Join(", ", connectedPids)}");

            if (_targetPid == -1 || !_sessions.ContainsKey(_targetPid) || !_sessions[_targetPid].IsConnected)
            {
                if (connectedPids.Count > 0)
                {
                    _targetPid = connectedPids[0];
                    _logger.LogInformation($"[AiCliService] Set default target PID to {_targetPid}");
                }
            }

            return connectedPids;
        }

        public async Task<int?> LaunchSessionAsync(string? workspace = null, Action<string>? onProgress = null)
        {
            _logger.LogInformation($"[AiCliService] LaunchSessionAsync starting (workspace: {workspace ?? "default"})");
            onProgress?.Invoke("Initializing launch sequence...");
            try
            {
                // Capture existing sessions before launch
                onProgress?.Invoke("Scanning for existing sessions...");
                var initialSessions = await DiscoverSessionsAsync(500, 1000); 

                string rootPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", ".."));
                string geminiDir = Path.GetFullPath(Path.Combine(rootPath, "..", "Tools", "gemini-cli"));

                if (string.IsNullOrWhiteSpace(workspace))
                {
                    workspace = Path.GetFullPath(Path.Combine(rootPath, ".."));
                }
                else
                {
                    workspace = Path.GetFullPath(workspace);
                }

                _logger.LogInformation($"[AiCliService] Launching process: cd /d {geminiDir} && node bundle/gemini.js --workspace {workspace}");
                onProgress?.Invoke($"Launching process in {workspace}...");

                string command = $"title OMNI_GEMINI_INTERACTIVE && cd /d \"{geminiDir}\" && node bundle/gemini.js --workspace {workspace} --yolo";
                string debugLog = Path.Combine(rootPath, "gemini_cli_debug.log");
                string finalCommand = $"set GEMINI_DEBUG_LOG_FILE={debugLog} && {command}";

                var startInfo = new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = $"/C \"{finalCommand}\"",
                    UseShellExecute = true,
                    CreateNoWindow = false,
                    WindowStyle = ProcessWindowStyle.Normal
                };

                var shellProcess = Process.Start(startInfo);
                _logger.LogInformation("[AiCliService] Process started. Waiting for it to establish named pipe...");
                onProgress?.Invoke("Process started. Waiting for connection...");

                // Wait for the process and its pipe
                for (int i = 0; i < 20; i++) 
                {
                    _logger.LogInformation($"[AiCliService] Launch check iteration {i+1}/20...");
                    await Task.Delay(1000);
                    
                    // Force a WMI refresh by resetting cache
                    _lastWmiDiscovery = DateTime.MinValue;
                    var currentSessions = await DiscoverSessionsAsync(1000, 5000); 
                    
                    var newPid = currentSessions.Except(initialSessions).FirstOrDefault();
                    if (newPid != 0)
                    {
                        string msg = $"Successfully connected to new session PID {newPid} after {i+1} seconds.";
                        _logger.LogInformation($"[AiCliService] {msg}");
                        onProgress?.Invoke(msg);

                        if (_sessions.TryGetValue(newPid, out var session))
                        {
                            session.SetShellProcess(shellProcess);
                        }

                        return newPid;
                    }
                    else
                    {
                        string msg = $"Waiting for new PID... (Iter {i+1}/20)";
                        _logger.LogInformation($"[AiCliService] {msg}");
                        onProgress?.Invoke(msg);
                    }
                }

                string errorMsg = "Failed to find new Gemini session after 20 seconds.";
                _logger.LogWarning($"[AiCliService] {errorMsg}");
                onProgress?.Invoke(errorMsg);
                return null;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "[AiCliService] Error launching Gemini CLI session");
                onProgress?.Invoke($"Error launching session: {ex.Message}");
                return null;
            }
        }

        private async Task EnsureSessionAsync(int pid, int timeoutMs)
        {
            if (_sessions.TryGetValue(pid, out var existing) && existing.IsConnected) return;

            _logger.LogInformation($"[AiCliService] Ensuring session for PID {pid} (timeout: {timeoutMs}ms)");
            
            DateTime startTime = DateTime.Now;
            try
            {
                var proc = Process.GetProcessById(pid);
                startTime = proc.StartTime;
            }
            catch (Exception ex)
            {
                _logger.LogDebug($"Could not get start time for process {pid}: {ex.Message}");
            }

            var session = new GeminiSession(pid, startTime, _logger, (p, text, finished, history) =>
            {
                ResponseReceived?.Invoke(this, new GeminiResponseEventArgs
                {
                    Pid = p,
                    Text = text,
                    IsFinished = finished,
                    IsHistory = history
                });
            });

            if (await session.ConnectAsync(timeoutMs))
            {
                _failedPids.TryRemove(pid, out _);
                await _sessionLock.WaitAsync();
                try
                {
                    if (!_sessions.ContainsKey(pid))
                    {
                        _sessions[pid] = session;
                        _logger.LogInformation($"[AiCliService] Connected to Gemini session PID {pid}");

                        // Load persistent name
                        var key = $"{startTime.Ticks}_{pid}";
                        var savedName = _settingsService.GetAiSessionName(key);
                        if (savedName != null)
                        {
                            _sessionNames[pid] = savedName;
                            _logger.LogInformation($"[AiCliService] Restored persistent name '{savedName}' for PID {pid}");
                        }
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
                _logger.LogWarning($"[AiCliService] Failed to connect to session for PID {pid}");
                _failedPids.AddOrUpdate(pid, (DateTime.Now, 1), (id, old) => (DateTime.Now, old.FailCount + 1));
                session.Dispose();
            }
        }

        public async Task SetTargetPidAsync(int pid)
        {
            _logger.LogInformation($"[AiCliService] Setting target PID to {pid}");
            if (!_sessions.ContainsKey(pid))
            {
                _logger.LogInformation($"[AiCliService] Target PID {pid} not in dictionary, discovering...");
                await DiscoverSessionsAsync(2000);
            }

            if (_sessions.ContainsKey(pid))
            {
                _targetPid = pid;
                _logger.LogInformation($"[AiCliService] Target PID set to {pid}");
            }
            else
            {
                _logger.LogWarning($"[AiCliService] Target PID {pid} still not found after discovery.");
            }
        }

        public int GetTargetPid() => _targetPid;

        public async Task<bool> SendPromptAsync(string text, int pid = -1)
        {
            _logger.LogInformation($"[AiCliService] SendPromptAsync: pid={pid}, _targetPid={_targetPid}, text='{(text.Length > 20 ? text.Substring(0, 20) + "..." : text)}'");
            int target = pid;

            if (target == -1) target = _targetPid;

            if (target == -1 || !_sessions.TryGetValue(target, out var targetSession) || !targetSession.IsConnected)
            {
                _logger.LogInformation($"[AiCliService] Target session {target} invalid or disconnected. Refreshing discovery...");
                var connected = await DiscoverSessionsAsync(2000);
                
                if (pid != -1)
                {
                     if (connected.Contains(pid)) target = pid;
                     else 
                     {
                         _logger.LogWarning($"[AiCliService] Requested PID {pid} not found after discovery.");
                         return false; 
                     }
                }
                else
                {
                    if (connected.Count > 0)
                    {
                        target = connected[0];
                        if (_targetPid == -1) 
                        {
                            _targetPid = target;
                            _logger.LogInformation($"[AiCliService] Auto-selected target PID: {_targetPid}");
                        }
                    }
                    else
                    {
                        _logger.LogInformation("[AiCliService] No active AI sessions. Auto-launching Gemini CLI...");
                        var newPid = await LaunchSessionAsync();
                        if (newPid.HasValue)
                        {
                            target = newPid.Value;
                            if (_targetPid == -1) _targetPid = target;
                            _logger.LogInformation($"[AiCliService] Auto-launched new session PID: {target}");
                        }
                        else 
                        {
                            _logger.LogError("[AiCliService] Failed to auto-launch AI session.");
                            return false;
                        }
                    }
                }
            }

            _logger.LogInformation($"[AiCliService] Sending prompt to PID {target}...");
            await _sessionLock.WaitAsync();
            try
            {
                if (_sessions.TryGetValue(target, out var session))
                {
                    bool result = await session.SendPromptAsync(text);
                    _logger.LogInformation($"[AiCliService] SendPromptAsync result for PID {target}: {result}");
                    return result;
                }
                _logger.LogWarning($"[AiCliService] Session {target} disappeared from dictionary.");
                return false;
            }
            finally
            {
                _sessionLock.Release();
            }
        }

        public async Task<bool> SendSpecialKeyAsync(string key, int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target == -1 || !_sessions.TryGetValue(target, out var session) || !session.IsConnected)
            {
                return false;
            }

            return await session.SendSpecialKeyAsync(key);
        }

        public async Task GetHistoryAsync(int pid)
        {
            if (_sessions.TryGetValue(pid, out var session))
            {
                await session.RequestHistoryAsync();
            }
        }

        public Task<bool> StopSessionAsync(int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (_sessions.TryGetValue(target, out var session))
            {
                var key = $"{session.StartTime.Ticks}_{target}";
                _settingsService.RemoveAiSessionName(key);

                session.Dispose();
                _sessions.TryRemove(target, out _);
                _sessionNames.TryRemove(target, out _);
                
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
                return Task.FromResult(true);
            }
            return Task.FromResult(false);
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
        private readonly DateTime _startTime;
        private readonly ILogger _logger;
        private readonly Action<int, string, bool, bool> _onResponse;
        private NamedPipeClientStream? _pipeClient;
        private StreamWriter? _writer;
        private CancellationTokenSource? _cts;
        private readonly StringBuilder _currentResponse = new();
        private readonly SemaphoreSlim _writeLock = new(1, 1);
        private Process? _shellProcess;

        public bool IsConnected => _pipeClient?.IsConnected ?? false;
        public int Pid => _pid;
        public DateTime StartTime => _startTime;

        public GeminiSession(int pid, DateTime startTime, ILogger logger, Action<int, string, bool, bool> onResponse)
        {
            _pid = pid;
            _startTime = startTime;
            _logger = logger;
            _onResponse = onResponse;
        }

        public void SetShellProcess(Process? process)
        {
            _shellProcess = process;
        }

        public async Task<bool> ConnectAsync(int timeoutMs)
        {
            _logger.LogInformation($"[GeminiSession] Connecting to pipe 'gemini-cli-{_pid}' (timeout: {timeoutMs}ms)");
            try
            {
                _pipeClient = new NamedPipeClientStream(".", $"gemini-cli-{_pid}", PipeDirection.InOut, PipeOptions.Asynchronous);
                await _pipeClient.ConnectAsync(timeoutMs);

                _writer = new StreamWriter(_pipeClient) { AutoFlush = true };
                _cts = new CancellationTokenSource();
                _ = Task.Run(() => ReadLoopAsync(_cts.Token));

                _logger.LogInformation($"[GeminiSession] Connected to PID {_pid}");
                return true;
            }
            catch (Exception ex)
            {
                _logger.LogWarning($"[GeminiSession] Could not connect to pipe for PID {_pid} within {timeoutMs}ms: {ex.Message}");
                return false;
            }
        }

        public async Task<bool> SendPromptAsync(string text)
        {
            _logger.LogDebug($"[GeminiSession] SendPromptAsync to PID {_pid}");
            _currentResponse.Clear();
            return await SendCommandAsync("prompt", text);
        }

        public async Task<bool> SendSpecialKeyAsync(string key)
        {
            _logger.LogDebug($"[GeminiSession] SendSpecialKeyAsync to PID {_pid}: {key}");
            return await SendCommandAsync("key", key);
        }

        public async Task RequestHistoryAsync()
        {
            _logger.LogDebug($"[GeminiSession] RequestHistoryAsync to PID {_pid}");
            await SendCommandAsync("getHistory", null);
        }

        private async Task<bool> SendCommandAsync(string command, string? text)
        {
            if (!IsConnected || _writer == null) 
            {
                                _logger.LogWarning($"[GeminiSession] Cannot send command '{command}' to PID {_pid}: Not connected");
                                return false;
                            }
                
                            await _writeLock.WaitAsync();
                            try
                            {
                                // Normalize path separators in prompt text to avoid double-escaping issues
                                string normalizedText = text?.Replace("\\\\", "/") ?? string.Empty;
                                var payload = JsonSerializer.Serialize(new { command, text = normalizedText });
                                Console.WriteLine($"[GeminiPipe WRITE] PID {_pid}: {payload}");
                                _logger.LogInformation($"[GeminiSession] Sending to PID {_pid}: {payload}");
                await _writer.WriteLineAsync(payload);
                await _writer.FlushAsync();
                return true;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"[GeminiSession] Error sending command to PID {_pid}");
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

            _logger.LogInformation($"[GeminiSession] Starting read loop for PID {_pid}");
            using var reader = new StreamReader(_pipeClient, Encoding.UTF8, false, 1024, leaveOpen: true);
            try
            {
                while (!token.IsCancellationRequested)
                {
                    var line = await reader.ReadLineAsync(token);
                    if (line == null) 
                    {
                        _logger.LogInformation($"[GeminiSession] Read null from PID {_pid}, pipe might be closed");
                        break;
                    }

                    Console.WriteLine($"[GeminiPipe RAW] PID {_pid}: {line}");
                    _logger.LogDebug($"[GeminiSession] Received from PID {_pid}: {line}");
                    try
                    {
                        var msg = JsonDocument.Parse(line);
                        if (msg.RootElement.TryGetProperty("type", out var type))
                        {
                            var typeStr = type.GetString();
                            var text = msg.RootElement.TryGetProperty("text", out var textProp) ? textProp.GetString() : null;

                            if (typeStr == "response")
                            {
                                if (text == null) continue;

                                if (text.Contains("[HISTORY_START]"))
                                {
                                    _logger.LogInformation($"[GeminiSession] Received history from PID {_pid}");
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
                                    _logger.LogInformation($"[GeminiSession] Turn finished for PID {_pid}");
                                    _onResponse(_pid, string.Empty, true, false);
                                }
                                else if (text == "[Command Handled]")
                                {
                                    _logger.LogDebug($"[GeminiSession] Command handled for PID {_pid}");
                                }
                                else
                                {
                                    _onResponse(_pid, text, false, false);
                                }
                            }
                            else if (typeStr == "codeDiff")
                            {
                                _logger.LogInformation($"[GeminiSession] Received codeDiff from PID {_pid}");
                                _onResponse(_pid, text ?? string.Empty, false, false);
                            }
                            else if (typeStr == "toolCall")
                            {
                                _logger.LogInformation($"[GeminiSession] Received toolCall from PID {_pid}");
                                _onResponse(_pid, text ?? string.Empty, false, false);
                            }
                        }
                    }
                    catch (JsonException ex)
                    {
                        _logger.LogWarning($"[GeminiSession] Error parsing JSON from PID {_pid}: {ex.Message}. Line: {line}");
                    }
                }
            }
            catch (OperationCanceledException) {{ }}
            catch (Exception ex)
            {
                _logger.LogError(ex, $"[GeminiSession] Read loop error for PID {_pid}");
            }
            finally
            {
                _logger.LogInformation($"[GeminiSession] Read loop finished for PID {_pid}");
            }
        }

        public void Dispose()
        {
            _cts?.Cancel();
            _writer?.Dispose();
            _pipeClient?.Dispose();
            _cts?.Dispose();
            _writeLock.Dispose();

            try
            {
                if (_shellProcess != null && !_shellProcess.HasExited)
                {
                    _shellProcess.Kill(true);
                    _logger.LogInformation($"[GeminiSession] Killed shell process for PID {_pid}");
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning($"[GeminiSession] Could not kill shell process for PID {_pid}: {ex.Message}");
            }
        }
    }
}