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
using Microsoft.Extensions.Configuration;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class GeminiResponseEventArgs : EventArgs
    {
        public int Pid { get; set; }
        public string Text { get; set; } = string.Empty;
        public bool IsFinished { get; set; }
        public bool IsHistory { get; set; }
        public bool IsCodeDiff { get; set; }
        public bool IsUser { get; set; }
    }

    public class GeminiDialogEventArgs : EventArgs
    {
        public int Pid { get; set; }
        public string Type { get; set; } = string.Empty;
        public string Prompt { get; set; } = string.Empty;
        public List<string>? Options { get; set; }
        public List<GeminiQuestion>? Questions { get; set; }
    }

    public class GeminiQuestion
    {
        [System.Text.Json.Serialization.JsonPropertyName("question")]
        public string Question { get; set; } = string.Empty;

        [System.Text.Json.Serialization.JsonPropertyName("type")]
        public string Type { get; set; } = "text"; // text, choice, yesno

        [System.Text.Json.Serialization.JsonPropertyName("header")]
        public string Header { get; set; } = string.Empty;

        [System.Text.Json.Serialization.JsonPropertyName("placeholder")]
        public string? Placeholder { get; set; }

        [System.Text.Json.Serialization.JsonPropertyName("multiSelect")]
        public bool? MultiSelect { get; set; }

        [System.Text.Json.Serialization.JsonPropertyName("options")]
        public List<GeminiOption>? Options { get; set; }
    }

    public class GeminiOption
    {
        [System.Text.Json.Serialization.JsonPropertyName("label")]
        public string Label { get; set; } = string.Empty;

        [System.Text.Json.Serialization.JsonPropertyName("description")]
        public string Description { get; set; } = string.Empty;
    }

    public class GeminiTurnEndEventArgs : EventArgs
    {
        public int Pid { get; set; }
        public string Reason { get; set; } = string.Empty;
        public string Category { get; set; } = string.Empty;
        public string? FinishReason { get; set; }
        public string? Message { get; set; }
        public string? Source { get; set; }
        public string? PromptId { get; set; }
        public string? WorkspacePath { get; set; }
        public string? WorkspaceName { get; set; }
        public string? Timestamp { get; set; }
    }

    public class AiSessionInfo
    {
        [System.Text.Json.Serialization.JsonPropertyName("pid")]
        public int Pid { get; set; }
        
        [System.Text.Json.Serialization.JsonPropertyName("name")]
        public string Name { get; set; } = string.Empty;
        
        [System.Text.Json.Serialization.JsonPropertyName("startTime")]
        public DateTime StartTime { get; set; }

        [System.Text.Json.Serialization.JsonPropertyName("workspace")]
        public string Workspace { get; set; } = string.Empty;
    }

    public class AiCliService : IDisposable
    {
        private readonly ILogger<AiCliService> _logger;
        private readonly HubSettingsService _settingsService;
        private readonly ProcessService _processService;
        private readonly HubMonitorService _hubMonitorService;
        private bool _debugMode;
        private readonly ConcurrentDictionary<int, GeminiSession> _sessions = new();
        private readonly ConcurrentDictionary<int, string> _sessionNames = new();
        private readonly ConcurrentDictionary<int, string> _workspaces = new();
        private readonly ConcurrentDictionary<int, string> _lastDialogTypes = new();
        private readonly ConcurrentDictionary<int, string> _tellPcContexts = new();
        private bool _isTriggeringTellPcFromHub = false;
        private string? _pendingTellPcContext = null;
        private readonly ConcurrentDictionary<int, (DateTime LastAttempt, int FailCount)> _failedPids = new();
        private readonly ConcurrentQueue<(string Text, int Pid)> _pendingPrompts = new();
        private DateTime _lastWmiDiscovery = DateTime.MinValue;
        private List<int> _cachedWmiPids = new();
        private List<(int Pid, string Cmd, int Parent)> _cachedRawGeminiInfo = new();
        private int _targetPid = -1;
        private bool _isLaunching = false;
        private readonly SemaphoreSlim _sessionLock = new(1, 1);
        private readonly SemaphoreSlim _discoveryLock = new(1, 1);
        private readonly SemaphoreSlim _launchLock = new(1, 1);

        public bool IsBusy => _sessionLock.CurrentCount == 0 || _discoveryLock.CurrentCount == 0 || _isLaunching;
        public bool IsDebugModeEnabled => _debugMode;

        public event EventHandler<GeminiResponseEventArgs>? ResponseReceived;
        public event EventHandler<GeminiDialogEventArgs>? DialogReceived;
        public event EventHandler<GeminiTurnEndEventArgs>? TurnEndedReceived;

        public AiCliService(ILogger<AiCliService> logger, HubSettingsService settingsService, ProcessService processService, HubMonitorService hubMonitorService, IConfiguration configuration)
        {
            _logger = logger;
            _settingsService = settingsService;
            _processService = processService;
            _hubMonitorService = hubMonitorService;
            _debugMode = _settingsService.Settings.AiDebugMode;

            _settingsService.SettingsChanged += (s, e) =>
            {
                _debugMode = _settingsService.Settings.AiDebugMode;
            };
            
            // Trigger initial discovery in background
            _ = Task.Run(async () => {
                await Task.Delay(2000); // Give Hub time to fully start
                await DiscoverSessionsAsync();
            });
        }

        public async Task FocusSessionAsync(int pid)
        {
            string? hint = GetTitleHint(pid);
            _processService.WinActivatePid(pid, hint);
            await Task.CompletedTask;
        }

        public async Task ToggleMonitorSessionAsync(int pid)
        {
            string? hint = GetTitleHint(pid);
            _processService.MoveWindowOpposite(pid, hint);
            await Task.CompletedTask;
        }

        private string? GetTitleHint(int pid)
        {
            if (_workspaces.TryGetValue(pid, out var ws) && !string.IsNullOrEmpty(ws)) return ws;
            if (_sessionNames.TryGetValue(pid, out var name)) return name;
            return null;
        }

        public async Task MoveSessionToMonitorAsync(int pid, int monitorIndex)
        {
            _processService.MoveWindowToMonitor(pid, monitorIndex);
            await Task.CompletedTask;
        }

        public Task SetSessionNameAsync(int pid, string name)
        {
            _sessionNames[pid] = name;
            if (_sessions.TryGetValue(pid, out var session))
            {
                var key = $"{session.StartTime.Ticks}_{pid}";
                _settingsService.SetAiSessionName(key, name);
            }
            return Task.CompletedTask;
        }

        public void SetTellPcContext(int pid, string context)
        {
            if (pid == -1)
            {
                _isTriggeringTellPcFromHub = true;
                _pendingTellPcContext = context;
            }
            else
            {
                _tellPcContexts[pid] = context;
            }
        }

        public void TryAutoRenameSession(int pid, string firstMessage)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target == -1 || string.IsNullOrWhiteSpace(firstMessage)) return;
            if (_sessionNames.ContainsKey(target)) return; 

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
                if (_sessions.TryGetValue(target, out var session2))
                {
                    var key = $"{session2.StartTime.Ticks}_{target}";
                    _settingsService.SetAiSessionName(key, name);
                }
            }
        }

        private string GenerateName(string message)
        {
            var prefixWords = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                "please", "can", "you", "i", "want", "to", "could", "would", "should", "tell", "give", "show", 
                "help", "with", "write", "create", "make", "find", "search", "check", "analyze", "suggest", 
                "summarize", "explain", "how", "what", "why", "when", "where", "who", "is", "are", "am", 
                "do", "does", "did", "for", "of", "in", "on", "at", "by", "this", "that", "will", "my", "your", 
                "it", "its", "from", "now", "here", "there", "me", "a", "an", "the"
            };

            var words = message.Split(new[] { ' ', '\t', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries);
            int startIndex = 0;
            while (startIndex < words.Length && prefixWords.Contains(words[startIndex].Trim(new[] { '.', ',', '?', '!', '"', '\'', ':', ';' })))
            {
                startIndex++;
            }
            if (startIndex >= words.Length) startIndex = 0;
            string baseName = string.Join(" ", words.Skip(startIndex).Take(3));
            if (baseName.Length > 12) return baseName.Substring(0, 12).Trim() + "..";
            return baseName;
        }

        public string GetSessionName(int pid)
        {
            if (_sessionNames.TryGetValue(pid, out var name)) return name;
            if (_workspaces.TryGetValue(pid, out var ws) && !string.IsNullOrEmpty(ws)) return ws;
            return $"Session {pid}";
        }

        public string? GetLastDialogType(int pid) => _lastDialogTypes.TryGetValue(pid, out var dt) ? dt : null;

        public IDictionary<int, string> GetSessionsWithNames()
        {
            var result = new Dictionary<int, string>();
            foreach (var pid in _sessions.Keys)
            {
                if (_sessions.TryGetValue(pid, out var session) && session.IsConnected)
                    result[pid] = GetSessionName(pid);
            }
            return result;
        }

        public List<AiSessionInfo> GetActiveSessions()
        {
            return _sessions.Values
                .OrderBy(s => s.StartTime)
                .Select(s => new AiSessionInfo
                {
                    Pid = s.Pid,
                    Name = GetSessionName(s.Pid),
                    StartTime = s.StartTime,
                    Workspace = _workspaces.TryGetValue(s.Pid, out var ws) ? ws : string.Empty
                })
                .ToList();
        }

        public async Task<List<int>> DiscoverSessionsAsync(int connectionTimeoutMs = 1000, int startupTimeout = 5000)
        {
            await _discoveryLock.WaitAsync();
            try
            {
                var pids = new List<int>();
                var now = DateTime.Now;

                foreach (var existingPid in _sessions.Keys.ToList())
                {
                    try {
                        var proc = Process.GetProcessById(existingPid);
                        if (proc == null || proc.HasExited) {
                            _sessions.TryRemove(existingPid, out var deadSession);
                            deadSession?.Dispose();
                        }
                    } catch {
                        _sessions.TryRemove(existingPid, out var deadSession);
                        deadSession?.Dispose();
                    }
                }

                if ((now - _lastWmiDiscovery).TotalSeconds < 10 && _cachedWmiPids.Any())
                {
                    pids = _cachedWmiPids.ToList();
                }
                else
                {
                    pids = await GetAllGeminiPidsAsync();
                    
                    var leafPids = new HashSet<int>();
                    foreach (var gp in _cachedRawGeminiInfo)
                    {
                        // If this process is a parent of another process in our list, 
                        // then that other process is a "child" (leaf-ward)
                        var child = _cachedRawGeminiInfo.FirstOrDefault(other => other.Parent == gp.Pid);
                        if (child.Pid != 0)
                        {
                            leafPids.Add(child.Pid);
                        }
                    }
                    // Filter out the leaves, keeping only the top-level node processes
                    pids = pids.Except(leafPids).ToList();
                    
                    _cachedWmiPids = pids.ToList();
                }

                foreach (var pid in _sessions.Keys.ToList())
                {
                    if (!pids.Contains(pid)) {
                        bool shouldRemove = true;
                        try { if (Process.GetProcessById(pid).HasExited) shouldRemove = true; else shouldRemove = false; } catch { shouldRemove = true; }
                        if (shouldRemove && _sessions.TryRemove(pid, out var session)) session.Dispose();
                    }
                }

                var pidsToConnect = pids.Where(p => (!_sessions.ContainsKey(p) || !_sessions[p].IsConnected)).ToList();
                if (pidsToConnect.Any())
                {
                    await Task.WhenAll(pidsToConnect.Select(p => EnsureSessionAsync(p, Math.Min(connectionTimeoutMs, 300))));
                }
                
                var connectedPids = _sessions.Where(s => s.Value.IsConnected).Select(s => s.Key).ToList();
                if (_targetPid == -1 || !_sessions.ContainsKey(_targetPid) || !_sessions[_targetPid].IsConnected)
                {
                    if (connectedPids.Count > 0) _targetPid = connectedPids[0];
                }
                return connectedPids;
            }
            finally { _discoveryLock.Release(); }
        }

        public async Task ReloadAiSessionsAsync(List<AiSessionInfo> androidSessions)
        {
            await DiscoverSessionsAsync(2000, 5000);
        }

        public async Task<int?> LaunchSessionAsync(string? workspace = null, Action<string>? onProgress = null, string? model = null, string? prepromptFile = null)
        {
            await _launchLock.WaitAsync();
            try 
            {
                if (_isLaunching) return null;
                _isLaunching = true;
                try
                {
                    var initialPids = await GetAllGeminiPidsAsync();
                    string rootPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", ".."));
                    string geminiDir = Path.GetFullPath(Path.Combine(rootPath, "..", "Tools", "omni-gemini-cli", "main")).TrimEnd('\\', '/');

                    string finalWorkspace = string.IsNullOrWhiteSpace(workspace) ? Path.GetFullPath(Path.Combine(rootPath, "..")) : Path.GetFullPath(workspace);
                    finalWorkspace = finalWorkspace.TrimEnd('\\', '/');
                    string bundlePath = Path.Combine(geminiDir, "bundle", "gemini.js");
                    string wsName = Path.GetFileName(finalWorkspace);

                    string tempTitle = $"OMNI_LAUNCHING_{Guid.NewGuid():N}";
                    string command = $"title {tempTitle} && cd /d \"{finalWorkspace}\" && node \"{bundlePath}\" --workspace \"{finalWorkspace.Replace("\\", "/")}\" --yolo";
                    if (!string.IsNullOrEmpty(model ?? _settingsService.Settings.DefaultAiModel)) command += $" --model {model ?? _settingsService.Settings.DefaultAiModel}";
                    if (!string.IsNullOrEmpty(prepromptFile)) command += $" --prepromptfile {prepromptFile.Replace("\\", "/")}";

                    _processService.ExecuteCommandNonAdmin("cmd.exe", $"/K \"set GEMINI_DEBUG_LOG_FILE={Path.Combine(rootPath, "gemini_cli_debug.log")} && set CLI_TITLE={wsName} && {command}\"");
                    
                    for (int i = 0; i < 40; i++) 
                    {
                        await Task.Delay(1000);
                        _lastWmiDiscovery = DateTime.MinValue;
                        var currentSessions = await DiscoverSessionsAsync(1000, 5000); 
                        var diffPids = currentSessions.Except(initialPids).ToList();
                        if (diffPids.Any())
                        {
                            int pid = diffPids.First();
                            _targetPid = pid;

                            // Set dynamic title to match gmi scheme: WorkspaceName [PID]
                            var finalWsName = _workspaces.TryGetValue(pid, out var ws) ? ws : wsName;
                            var finalTitle = $"{finalWsName} [{pid}]";
                            
                            _processService.SetWindowTitle(pid, finalTitle, tempTitle);

                            return pid;
                        }
                    }
                    return null;
                }
                finally { _isLaunching = false; }
            }
            finally { _launchLock.Release(); }
        }

        private async Task EnsureSessionAsync(int pid, int timeoutMs)
        {
            if (_sessions.TryGetValue(pid, out var existing) && existing.IsConnected) return;
            
            _logger.LogInformation($"[AiCliService] Ensuring session for PID {pid} (timeout: {timeoutMs}ms)");
            DateTime startTime = DateTime.Now;
            try { startTime = Process.GetProcessById(pid).StartTime; } catch { }

            var session = new GeminiSession(pid, startTime, _logger, _debugMode, (p, text, finished, history, isCodeDiff, isUser) =>
            {
                ResponseReceived?.Invoke(this, new GeminiResponseEventArgs { Pid = p, Text = text, IsFinished = finished, IsHistory = history, IsCodeDiff = isCodeDiff, IsUser = isUser });
            }, (p, type, prompt, options, questions) =>
            {
                _lastDialogTypes[p] = type;
                DialogReceived?.Invoke(this, new GeminiDialogEventArgs { Pid = p, Type = type, Prompt = prompt, Options = options, Questions = questions });
            }, (p, r, c, fr, m, s, pi, wp, wn, ts) =>
            {
                if (!string.IsNullOrWhiteSpace(wn)) _workspaces[p] = wn;
                TurnEndedReceived?.Invoke(this, new GeminiTurnEndEventArgs { Pid = p, Reason = r, Category = c, FinishReason = fr, Message = m, Source = s, PromptId = pi, WorkspacePath = wp, WorkspaceName = wn, Timestamp = ts });
            });

            if (await session.ConnectAsync(timeoutMs))
            {
                if ((DateTime.Now - startTime).TotalSeconds > 15) session.MarkAsReady();
                _sessions[pid] = session;
                _logger.LogInformation($"[AiCliService] Connected to Gemini session PID {pid}");
                var savedName = _settingsService.GetAiSessionName($"{startTime.Ticks}_{pid}");
                if (savedName != null) _sessionNames[pid] = savedName;
            } else session.Dispose();
        }

        private async Task<List<int>> GetAllGeminiPidsAsync()
        {
            var now = DateTime.Now;
            var pids = new List<int>();
            try {
                string query = "SELECT ProcessId, CommandLine, ParentProcessId FROM Win32_Process WHERE Name LIKE 'node%'";
                using var searcher = new ManagementObjectSearcher(query);
                using var collection = searcher.Get();
                var raw = new List<(int Pid, string CommandLine, int ParentPid)>();
                foreach (var process in collection) {
                    var cmd = process["CommandLine"]?.ToString();
                    var pid = Convert.ToInt32(process["ProcessId"]);
                    var ppid = Convert.ToInt32(process["ParentProcessId"]);
                    if (cmd != null && (cmd.ToLower().Contains("gemini.js") || cmd.ToLower().Contains("omni_gemini"))) {
                        raw.Add((Pid: pid, CommandLine: cmd, ParentPid: ppid));
                        if (cmd.Contains("--workspace")) {
                            var parts = cmd.Split(new[] { "--workspace" }, StringSplitOptions.None);
                            if (parts.Length > 1) {
                                var ws = parts[1].Trim().Split(' ')[0].Trim('\"', '\'');
                                try { _workspaces[pid] = Path.GetFileName(ws.TrimEnd('\\', '/')); } catch { _workspaces[pid] = ws; }
                            }
                        }
                    }
                }
                _cachedRawGeminiInfo = raw;
                // Filter out any PIDs that are children of another Gemini node process in our list
                var allGeminiPids = new HashSet<int>(raw.Select(r => r.Pid));
                pids = raw.Where(r => !allGeminiPids.Contains(r.ParentPid))
                          .Select(r => r.Pid)
                          .ToList();
                _cachedWmiPids = pids;
                _lastWmiDiscovery = now;
            } catch (Exception ex) { _logger.LogError(ex, "Error in GetAllGeminiPidsAsync"); }
            return pids;
        }

        public async Task<bool> SendPromptAsync(string text, int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target != -1 && _sessions.TryGetValue(target, out var s) && s.IsConnected)
            {
                if (!s.IsReady) await s.WaitUntilReadyAsync(30000);
                return await s.SendPromptAsync(text);
            }
            return false;
        }

        public async Task<bool> SendSpecialKeyAsync(string key, int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target != -1 && _sessions.TryGetValue(target, out var s) && s.IsConnected)
                return await s.SendSpecialKeyAsync(key);
            return false;
        }

        public async Task<bool> SendDialogResponseAsync(string response, int pid = -1, string? dialogType = null)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target != -1 && _sessions.TryGetValue(target, out var s) && s.IsConnected)
                return await s.SendDialogResponseAsync(response, dialogType);
            return false;
        }

        public async Task GetHistoryAsync(int pid, int maxChars = 0)
        {
            if (_sessions.TryGetValue(pid, out var s)) await s.RequestHistoryAsync(maxChars);
        }

        public async Task<bool> StopSessionAsync(int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (_sessions.TryRemove(target, out var s)) {
                s.Dispose();
                _sessionNames.TryRemove(target, out _);
                _workspaces.TryRemove(target, out _);
                return true;
            }
            return false;
        }

        public int GetTargetPid() => _targetPid;

        public async Task<bool> SetTargetPidAsync(int pid)
        {
            if (_sessions.ContainsKey(pid)) {
                _targetPid = pid;
                return true;
            }
            return false;
        }

        public void KillAllGeminiProcesses()
        {
            foreach (var s in _sessions.Values) s.Dispose();
            _sessions.Clear();
            _sessionNames.Clear();
            _workspaces.Clear();
        }

        public void Dispose() { foreach (var s in _sessions.Values) s.Dispose(); _sessions.Clear(); }
    }

    internal class GeminiSession : IDisposable
    {
        private static string EscapeContentForJson(string s)
        {
            var sb = new StringBuilder();
            foreach (char c in s)
            {
                if (c < ' ')
                {
                    switch (c)
                    {
                        case '\b': sb.Append("\\b"); break;
                        case '\f': sb.Append("\\f"); break;
                        case '\n': sb.Append("\\n"); break;
                        case '\r': sb.Append("\\r"); break;
                        case '\t': sb.Append("\\t"); break;
                        default:
                            sb.Append("\\u" + ((int)c).ToString("x4"));
                            break;
                    }
                }
                else if (c == '\\')
                {
                    sb.Append("\\\\");
                }
                else
                {
                    sb.Append(c);
                }
            }
            return sb.ToString();
        }

        private readonly int _pid;
        private readonly string _sid;
        private readonly DateTime _startTime;
        private readonly ILogger _logger;
        private readonly bool _debugMode;
        private readonly Action<int, string, bool, bool, bool, bool> _onResponse;
        private readonly Action<int, string, string, List<string>?, List<GeminiQuestion>?> _onDialog;
        private readonly Action<int, string, string, string?, string?, string?, string?, string?, string?, string?> _onTurnEnd;
        private NamedPipeClientStream? _pipeClient;
        private StreamWriter? _writer;
        private CancellationTokenSource? _cts;
        private readonly StringBuilder _historyBuffer = new();
        private bool _isCapturingHistory = false;
        private readonly HashSet<string> _recentlyBroadcastMessages = new();
        private bool _isReady = false;
        private readonly TaskCompletionSource<bool> _readyTcs = new();

        public bool IsConnected => _pipeClient?.IsConnected ?? false;
        public bool IsReady => _isReady;
        public int Pid => _pid;
        public DateTime StartTime => _startTime;

        public GeminiSession(int pid, DateTime startTime, ILogger logger, bool debugMode, Action<int, string, bool, bool, bool, bool> onResponse, Action<int, string, string, List<string>?, List<GeminiQuestion>?> onDialog, Action<int, string, string, string?, string?, string?, string?, string?, string?, string?> onTurnEnd)
        {
            _pid = pid; _sid = Guid.NewGuid().ToString().Substring(0, 4); _startTime = startTime; _logger = logger; _debugMode = debugMode; _onResponse = onResponse; _onDialog = onDialog; _onTurnEnd = onTurnEnd;
        }

        public async Task<bool> ConnectAsync(int timeoutMs)
        {
            try {
                _pipeClient = new NamedPipeClientStream(".", $"omni-gemini-cli-{_pid}", PipeDirection.InOut, PipeOptions.Asynchronous);
                await _pipeClient.ConnectAsync(timeoutMs);
                _writer = new StreamWriter(_pipeClient) { AutoFlush = true };
                _cts = new CancellationTokenSource();
                _ = Task.Run(() => ReadLoopAsync(_cts.Token));
                return true;
            } catch { return false; }
        }

        public void MarkAsReady() { if (!_isReady) { _isReady = true; _readyTcs.TrySetResult(true); } }
        public async Task<bool> WaitUntilReadyAsync(int ms) { return await Task.WhenAny(_readyTcs.Task, Task.Delay(ms)) == _readyTcs.Task; }

        public async Task<bool> SendPromptAsync(string text)
        {
            if (!IsConnected || _writer == null) return false;
            try {
                await _writer.WriteLineAsync(JsonSerializer.Serialize(new { command = "prompt", text = text }));
                return true;
            } catch { return false; }
        }

        public async Task<bool> SendSpecialKeyAsync(string key)
        {
            if (!IsConnected || _writer == null) return false;
            try {
                await _writer.WriteLineAsync(JsonSerializer.Serialize(new { command = "key", text = key }));
                return true;
            } catch { return false; }
        }

        public async Task<bool> SendDialogResponseAsync(string response, string? dialogType = null)
        {
            if (!IsConnected || _writer == null) return false;
            try {
                await _writer.WriteLineAsync(JsonSerializer.Serialize(new { command = "dialogResponse", response = response, dialogType = dialogType }));
                return true;
            } catch { return false; }
        }

        public async Task RequestHistoryAsync(int maxChars = 0)
        {
            if (!IsConnected || _writer == null) return;
            await _writer.WriteLineAsync(JsonSerializer.Serialize(new { command = "getHistory", text = "", maxChars = maxChars }));
        }

        private async Task ReadLoopAsync(CancellationToken token)
        {
            byte[] buffer = new byte[65536];
            StringBuilder msgAccumulator = new StringBuilder();
            try {
                while (!token.IsCancellationRequested && _pipeClient!.IsConnected) {
                    int read = await _pipeClient.ReadAsync(buffer, 0, buffer.Length, token);
                    if (read == 0) break;
                    string chunk = Encoding.UTF8.GetString(buffer, 0, read);
                    int lastPos = 0, newlinePos;
                    while ((newlinePos = chunk.IndexOf('\n', lastPos)) != -1) {
                        msgAccumulator.Append(chunk.Substring(lastPos, newlinePos - lastPos));
                        string completeMsg = msgAccumulator.ToString();
                        msgAccumulator.Clear();
                        lastPos = newlinePos + 1;
                        if (string.IsNullOrWhiteSpace(completeMsg)) continue;
                        try {
                            var msg = JsonDocument.Parse(completeMsg);
                            var type = msg.RootElement.GetProperty("type").GetString();
                            var text = msg.RootElement.TryGetProperty("text", out var t) ? t.GetString() : null;

                            if (type == "history") {
                                if (text != null) {
                                    string historyJson = "[]";
                                    try {
                                        string rawContent = msg.RootElement.GetProperty("text").GetRawText();
                                        if (rawContent.StartsWith("\"") && rawContent.EndsWith("\""))
                                            rawContent = rawContent.Substring(1, rawContent.Length - 2);

                                        _logger.LogInformation($"[GeminiSession] SID: {_sid} | Raw history sample (first 100): {(rawContent.Length > 100 ? rawContent.Substring(0, 100) : rawContent)}");

                                        // STRIP MARKERS - Robust Regex approach
                                        string currentContent = rawContent;
                                        // Handle possible double-escaped brackets or plain brackets
                                        var match = System.Text.RegularExpressions.Regex.Match(currentContent, @"(?:\\\[|\[)HISTORY_START(?:\\\]|\])(.*)(?:\\\[|\[)HISTORY_END(?:\\\]|\])", System.Text.RegularExpressions.RegexOptions.Singleline);
                                        if (match.Success)
                                        {
                                            currentContent = match.Groups[1].Value;
                                        }
                                        else
                                        {
                                            // Fallback: manual sequential stripping
                                            int sIdx = currentContent.IndexOf("HISTORY_START");
                                            if (sIdx != -1) {
                                                currentContent = currentContent.Substring(sIdx + "HISTORY_START".Length);
                                                if (currentContent.StartsWith("]") || currentContent.StartsWith("\\]")) 
                                                    currentContent = currentContent.Substring(currentContent.IndexOf(']') + 1);
                                            }
                                            int eIdx = currentContent.LastIndexOf("HISTORY_END");
                                            if (eIdx != -1) {
                                                currentContent = currentContent.Substring(0, eIdx);
                                                int lastBracket = currentContent.LastIndexOf('[');
                                                if (lastBracket != -1) currentContent = currentContent.Substring(0, lastBracket);
                                            }
                                        }

                                        List<Dictionary<string, string>>? history = null;
                                        for (int depth = 0; depth <= 4; depth++)
                                        {
                                            // 1. Try direct parse (cleanest)
                                            try {
                                                history = JsonSerializer.Deserialize<List<Dictionary<string, string>>>(currentContent, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                                                if (history != null) {
                                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | History successfully parsed at unescape depth {depth}. Items: {history.Count}");
                                                    historyJson = currentContent;
                                                    break;
                                                }
                                            } catch (Exception directEx) {
                                                _logger.LogDebug($"[GeminiSession] SID: {_sid} | Direct parse depth {depth} failed: {directEx.Message}");
                                                
                                                // 2. Try with rescue (escapes literal control chars)
                                                try {
                                                    string sanitized = EscapeContentForJson(currentContent);
                                                    history = JsonSerializer.Deserialize<List<Dictionary<string, string>>>(sanitized, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                                                    if (history != null) {
                                                        _logger.LogInformation($"[GeminiSession] SID: {_sid} | History successfully parsed with rescue at unescape depth {depth}. Items: {history.Count}");
                                                        historyJson = sanitized;
                                                        break;
                                                    }
                                                } catch { }

                                                // 3. Unescape one level for next iteration
                                                try {
                                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Unescaping one level (current depth: {depth})...");
                                                    string toDeserialize = currentContent.StartsWith("\"") ? currentContent : "\"" + currentContent + "\"";
                                                    var unescaped = JsonSerializer.Deserialize<string>(toDeserialize);
                                                    if (unescaped == null || unescaped == currentContent) break;
                                                    currentContent = unescaped;
                                                } catch (Exception unescapeEx) {
                                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Unescape at depth {depth} FAILED: {unescapeEx.Message}");
                                                    break; 
                                                }
                                            }
                                        }
                                        if (history == null) throw new JsonException("Could not parse history list.");
                                    } catch (Exception ex) {
                                        string sample = text.Length > 200 ? text.Substring(0, 200) : text;
                                        _logger.LogError(ex, $"[GeminiSession] SID: {_sid} | History parsing FAILED: {ex.Message}. Sample: {sample}");
                                        historyJson = "[{\"sender\":\"System\",\"text\":\"Error: Failed to process history. \"}]";
                                    }
                                    _onResponse(_pid, historyJson, true, true, false, false);
                                }
                            }
                            else if (type == "response") {
                                if (text == null) continue;
                                if (text.Contains("[HISTORY_START]")) {
                                    _isCapturingHistory = true; _historyBuffer.Clear(); _recentlyBroadcastMessages.Clear(); 
                                    string rawContent = msg.RootElement.GetProperty("text").GetRawText();
                                    int startIdx = rawContent.IndexOf("[HISTORY_START]");
                                    if (startIdx != -1) _historyBuffer.Append(rawContent.Substring(startIdx + "[HISTORY_START]".Length));
                                }
                                else if (_isCapturingHistory) {
                                    string rawContent = msg.RootElement.GetProperty("text").GetRawText();
                                    if (rawContent.StartsWith("\"") && rawContent.EndsWith("\"")) _historyBuffer.Append(rawContent.Substring(1, rawContent.Length - 2));
                                    else _historyBuffer.Append(rawContent);
                                }
                                if (_isCapturingHistory && text.Contains("[HISTORY_END]")) {
                                    _isCapturingHistory = false;
                                    string fullHistoryBuffer = _historyBuffer.ToString();
                                    int endIdx = fullHistoryBuffer.IndexOf("[HISTORY_END]");
                                    string rawListJson = endIdx != -1 ? fullHistoryBuffer.Substring(0, endIdx) : fullHistoryBuffer;
                                    if (rawListJson.EndsWith("\"")) rawListJson = rawListJson.Substring(0, rawListJson.Length - 1);
                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | HISTORY_END received. Extracted raw length: {rawListJson.Length}");
                                    string historyJson = "[]";
                                    try {
                                        string currentContent = rawListJson;
                                        List<Dictionary<string, string>>? history = null;
                                        for (int depth = 0; depth <= 4; depth++) {
                                            try {
                                                history = JsonSerializer.Deserialize<List<Dictionary<string, string>>>(currentContent, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                                                if (history != null) { historyJson = currentContent; break; }
                                            } catch {
                                                try {
                                                    string sanitized = EscapeContentForJson(currentContent);
                                                    history = JsonSerializer.Deserialize<List<Dictionary<string, string>>>(sanitized, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                                                    if (history != null) { historyJson = sanitized; break; }
                                                } catch {
                                                    try {
                                                        string toDeserialize = currentContent.StartsWith("\"") ? currentContent : "\"" + currentContent + "\"";
                                                        var unescaped = JsonSerializer.Deserialize<string>(toDeserialize);
                                                        if (unescaped == null || unescaped == currentContent) break;
                                                        currentContent = unescaped;
                                                    } catch { break; }
                                                }
                                            }
                                        }
                                    } catch { }
                                    _onResponse(_pid, historyJson, true, true, false, false);
                                    _historyBuffer.Clear();
                                }
                                else if (!_isCapturingHistory) {
                                    if (text != "[Command Handled]" && _recentlyBroadcastMessages.Add(text))
                                        _onResponse(_pid, text, false, false, false, false);
                                }
                            }
                            else if (type == "thought") { if (text != null) _onResponse(_pid, $"Thinking: {text}", false, false, false, false); }
                            else if (type == "dialog") {
                                var dt = msg.RootElement.GetProperty("dialogType").GetString();
                                if (dt == "ready") MarkAsReady();
                                _onDialog(_pid, dt ?? "unknown", text ?? "", null, null);
                            }
                            else if (type == "turn_end") { _onTurnEnd(_pid, msg.RootElement.GetProperty("reason").GetString() ?? "unknown", "unknown", null, null, null, null, null, null, null); }
                        } catch { }
                    }
                    if (lastPos < chunk.Length) msgAccumulator.Append(chunk.Substring(lastPos));
                }
            } catch { }
        }

        public void Dispose() { _cts?.Cancel(); _writer?.Dispose(); _pipeClient?.Dispose(); }
    }
}
