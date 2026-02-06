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
        private readonly ConcurrentDictionary<int, string> _tellPcContexts = new();
        private bool _isTriggeringTellPcFromHub = false;
        private string? _pendingTellPcContext = null;
        private readonly ConcurrentDictionary<int, (DateTime LastAttempt, int FailCount)> _failedPids = new();
        private readonly ConcurrentQueue<(string Text, int Pid)> _pendingPrompts = new();
        private DateTime _lastWmiDiscovery = DateTime.MinValue;
        private List<int> _cachedWmiPids = new();
        private List<(int Pid, string CommandLine, int ParentPid)> _cachedRawGeminiInfo = new();
        private int _targetPid = -1;
        private bool _isLaunching = false;
        private readonly SemaphoreSlim _sessionLock = new(1, 1);
        private readonly SemaphoreSlim _discoveryLock = new(1, 1);

        public bool IsBusy => _sessionLock.CurrentCount == 0 || _discoveryLock.CurrentCount == 0 || _isLaunching;
        public bool IsDebugModeEnabled => _debugMode;

        public event EventHandler<GeminiResponseEventArgs>? ResponseReceived;
        public event EventHandler<GeminiDialogEventArgs>? DialogReceived;

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
            _logger.LogInformation($"[AiCliService] Focusing session PID {pid}");
            
            // Use the generalized WinActivatePid which now handles nested processes and terminal hosts
            _processService.WinActivatePid(pid);

            await Task.CompletedTask;
        }

        public async Task MoveSessionToMonitorAsync(int pid, int monitorIndex)
        {
            _logger.LogInformation($"[AiCliService] MoveSessionToMonitorAsync (General): PID {pid}, Monitor {monitorIndex}");
            
            // Instead of a python script, we use the Hub's own generalized logic
            // MoveWindowOpposite is a toggle, but we can easily add a MoveToMonitor(pid, index)
            _processService.MoveWindowToMonitor(pid, monitorIndex);
            
            await Task.CompletedTask;
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
            if (_sessionNames.TryGetValue(pid, out var name)) return name;
            if (_workspaces.TryGetValue(pid, out var ws) && !string.IsNullOrEmpty(ws)) return ws;
            return $"Session {pid}";
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
                _logger.LogInformation($"[AiCliService] DiscoverSessionsAsync started (timeout: {connectionTimeoutMs}ms, startup: {startupTimeout}ms). IsLaunching={_isLaunching}");
                var sw = Stopwatch.StartNew();
                var pids = new List<int>();
                var now = DateTime.Now;

                // Clean up old failed PIDs (older than 5 minutes)
                foreach (var kp in _failedPids.Where(kvp => (now - kvp.Value.LastAttempt).TotalMinutes > 5).ToList())
                {
                    _failedPids.TryRemove(kp.Key, out _);
                }

                // Clean up sessions whose processes have died
                foreach (var existingPid in _sessions.Keys.ToList())
                {
                    try
                    {
                        var proc = Process.GetProcessById(existingPid);
                        if (proc == null || proc.HasExited)
                        {
                            _logger.LogInformation($"[AiCliService] Removing session PID {existingPid} because process has exited.");
                            _sessions.TryRemove(existingPid, out var deadSession);
                            deadSession?.Dispose();
                        }
                    }
                    catch (ArgumentException)
                    {
                        _logger.LogInformation($"[AiCliService] Removing session PID {existingPid} because process no longer exists.");
                        _sessions.TryRemove(existingPid, out var deadSession);
                        deadSession?.Dispose();
                    }
                }

                // Cache WMI for 10 seconds to avoid spamming slow queries
                if ((now - _lastWmiDiscovery).TotalSeconds < 10 && _cachedWmiPids.Any())
                {
                    _logger.LogDebug("[AiCliService] Using cached WMI discovery results");
                    pids = _cachedWmiPids.ToList();
                }
                else
                {
                    pids = await GetAllGeminiPidsAsync();
                    
                    // Identify wrappers vs leaves
                    var parentPids = new HashSet<int>();
                    foreach (var gp in _cachedRawGeminiInfo)
                    {
                        // Connected sessions are never treated as wrappers
                        if (_sessions.TryGetValue(gp.Pid, out var s) && s.IsConnected) continue;

                        var child = _cachedRawGeminiInfo.FirstOrDefault(other => other.ParentPid == gp.Pid);
                        if (child.Pid != 0)
                        {
                            _logger.LogInformation($"[AiCliService] Discovery: Skipping wrapper PID {gp.Pid} (child {child.Pid} found)");
                            parentPids.Add(gp.Pid);
                        }
                    }

                    // Remove wrappers from pids
                    pids = pids.Except(parentPids).ToList();
                    _cachedWmiPids = pids.ToList();
                }

                _logger.LogDebug($"[AiCliService] Discovery phase took {sw.ElapsedMilliseconds}ms. Found {pids.Count} potential PIDs.");

                // Clean up stale sessions
                foreach (var pid in _sessions.Keys)
                {
                    if (!pids.Contains(pid))
                    {
                        bool shouldRemove = true;
                        try
                        {
                            var proc = Process.GetProcessById(pid);
                            if (!proc.HasExited) shouldRemove = false;
                        }
                        catch { }

                        if (shouldRemove)
                        {
                            if (_sessions.TryRemove(pid, out var session))
                            {
                                session.Dispose();
                                _logger.LogInformation($"[AiCliService] Removed stale session PID {pid}. IsLaunching={_isLaunching}");
                            }
                        }
                    }
                }

                // Clean up stale names and workspaces for PIDs no longer present
                foreach (var pid in _sessionNames.Keys.ToList())
                {
                    if (!pids.Contains(pid))
                    {
                        _sessionNames.TryRemove(pid, out _);
                        _workspaces.TryRemove(pid, out _);
                    }
                }

                // Ensure we have sessions for all PIDs found
                // Filter pidsToConnect to ONLY those where the pipe actually exists to avoid timeout penalties
                var pidsToConnect = pids.Where(p => 
                    (!_sessions.ContainsKey(p) || !_sessions[p].IsConnected) && 
                    File.Exists($@"\\.\pipe\omni-gemini-cli-{p}")
                ).ToList();

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
            finally
            {
                _discoveryLock.Release();
            }
        }

        public async Task ReloadAiSessionsAsync(List<AiSessionInfo> androidSessions)
        {
            _logger.LogInformation("[AiCliService] ReloadAiSessionsAsync requested. Generating report...");
            
            var report = new StringBuilder();
            report.AppendLine("================================================================================");
            report.AppendLine("                      OMNI SESSION DEBUG REPORT");
            report.AppendLine($"                      GENERATED: {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
            report.AppendLine("================================================================================");
            report.AppendLine();

            report.AppendLine("ANDROID REPORTED SESSIONS:");
            if (androidSessions == null || androidSessions.Count == 0)
            {
                report.AppendLine("  (NONE)");
            }
            else
            {
                foreach (var s in androidSessions)
                {
                    report.AppendLine($"  PID: {s.Pid,-8} NAME: {s.Name,-20} START: {s.StartTime:HH:mm:ss} WS: {s.Workspace}");
                }
            }
            report.AppendLine();

            var hubSessions = GetActiveSessions();
            report.AppendLine("HUB CURRENT SESSIONS:");
            if (hubSessions.Count == 0)
            {
                report.AppendLine("  (NONE)");
            }
            else
            {
                foreach (var s in hubSessions)
                {
                    report.AppendLine($"  PID: {s.Pid,-8} NAME: {s.Name,-20} START: {s.StartTime:HH:mm:ss} WS: {s.Workspace}");
                }
            }
            report.AppendLine();

            report.AppendLine("COMPARISON ANALYSIS:");
            var androidPids = androidSessions?.Select(s => s.Pid).ToHashSet() ?? new HashSet<int>();
            var hubPids = hubSessions.Select(s => s.Pid).ToHashSet();

            var phantomOnAndroid = androidPids.Except(hubPids).ToList();
            var hiddenFromAndroid = hubPids.Except(androidPids).ToList();

            if (phantomOnAndroid.Count == 0 && hiddenFromAndroid.Count == 0)
            {
                report.AppendLine("  STATUS: MATCHED. Hub and Android see the same sessions.");
            }
            else
            {
                if (phantomOnAndroid.Count > 0)
                {
                    report.AppendLine($"  PHANTOM SESSIONS (On Android, not on Hub): {string.Join(", ", phantomOnAndroid)}");
                    foreach (var pid in phantomOnAndroid)
                    {
                        bool existsInSystem = false;
                        try { existsInSystem = Process.GetProcesses().Any(p => p.Id == pid); } catch { }
                        report.AppendLine($"    - PID {pid}: System Process Exists? {existsInSystem}");
                    }
                }
                if (hiddenFromAndroid.Count > 0)
                {
                    report.AppendLine($"  HIDDEN SESSIONS (On Hub, not on Android): {string.Join(", ", hiddenFromAndroid)}");
                }
            }
            report.AppendLine();

            report.AppendLine("SYSTEM PROCESS SCAN:");
            try
            {
                var geminiProcesses = Process.GetProcesses()
                    .Where(p => p.ProcessName.Contains("node", StringComparison.OrdinalIgnoreCase))
                    .ToList();
                
                report.AppendLine($"  Found {geminiProcesses.Count} node processes in system.");
            }
            catch (Exception ex)
            {
                report.AppendLine($"  Failed to scan processes: {ex.Message}");
            }
            report.AppendLine();

            report.AppendLine("ACTION: Force refreshing discovery...");
            var finalPids = await DiscoverSessionsAsync(2000, 5000);
            report.AppendLine($"  Discovery finished. Now active: {string.Join(", ", finalPids)}");
            report.AppendLine();
            report.AppendLine("================================================================================");

            try
            {
                // Write to root of project
                var rootPath = AppDomain.CurrentDomain.BaseDirectory;
                // Try to find the root where OmniSync.Hub folder is or similar
                // Based on context, root is D:\SSDProjects\Omni
                var fileName = "OMNI_SESSION_DEBUG_REPORT.LOG";
                var filePath = Path.Combine(rootPath, fileName);
                
                // If we are in bin/Debug/net9.0, go up
                for (int i = 0; i < 4; i++)
                {
                    if (File.Exists(Path.Combine(rootPath, "OmniSync.Hub.sln")) || Directory.Exists(Path.Combine(rootPath, ".git")))
                    {
                        filePath = Path.Combine(rootPath, fileName);
                        break;
                    }
                    var parent = Directory.GetParent(rootPath);
                    if (parent == null) break;
                    rootPath = parent.FullName;
                }

                await File.WriteAllTextAsync(filePath, report.ToString());
                _logger.LogInformation($"[AiCliService] Debug report written to: {filePath}");
                Console.WriteLine($"[AiCliService] Debug report written to: {filePath}");
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "[AiCliService] Failed to write debug report file.");
            }
        }

        private void TryExtractWorkspaceAndName(int pid, string commandLine)
        {
            try
            {
                string? workspace = null;
                var parts = commandLine.Split(' ');
                for (int i = 0; i < parts.Length - 1; i++)
                {
                    if (parts[i] == "--workspace" || parts[i] == "-w")
                    {
                        workspace = parts[i + 1].Trim('\"');
                        break;
                    }
                }

                if (!string.IsNullOrEmpty(workspace))
                {
                    string dirName = Path.GetFileName(workspace.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
                    if (string.IsNullOrEmpty(dirName)) dirName = "Root";
                    _sessionNames[pid] = GetUniqueSessionName(dirName, pid);
                }
            }
            catch (Exception ex)
            {
                _logger.LogDebug($"Failed to extract workspace from command line for PID {pid}: {ex.Message}");
            }
        }

        private string GetUniqueSessionName(string baseName, int pid)
        {
            // If the PID already has a name that matches our base, keep it
            if (_sessionNames.TryGetValue(pid, out var existingName) && existingName.StartsWith(baseName, StringComparison.OrdinalIgnoreCase))
            {
                return existingName;
            }

            string candidate = baseName;
            int counter = 1;
            
            // Check against ALL known names to ensure uniqueness during discovery
            while (_sessionNames.Any(kvp => kvp.Key != pid && 
                                           kvp.Value.Equals(candidate, StringComparison.OrdinalIgnoreCase)))
            {
                candidate = $"{baseName} ({++counter})";
            }
            return candidate;
        }

        private readonly SemaphoreSlim _launchLock = new(1, 1);

        public async Task<int?> LaunchSessionAsync(string? workspace = null, Action<string>? onProgress = null, string? model = null, string? prepromptFile = null)
        {
            await _launchLock.WaitAsync();
            try 
            {
                if (_isLaunching)
                {
                    _logger.LogWarning("[AiCliService] Already launching a session. Ignoring request.");
                    onProgress?.Invoke("Already launching a session...");
                    return null;
                }

                _isLaunching = true;
                _logger.LogInformation($"[AiCliService] LaunchSessionAsync starting (workspace: {workspace ?? "default"}, model: {model ?? "default"}, prepromptFile: {prepromptFile ?? "none"})");
                onProgress?.Invoke("Initializing launch sequence...");
                try
                {
                    // Capture ALL existing Gemini PIDs before launch (even if not yet connected)
                    onProgress?.Invoke("Scanning for existing sessions...");
                    var initialPids = await GetAllGeminiPidsAsync();

                                string rootPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", ".."));
                                _logger.LogInformation($"[AiCliService] Computed rootPath: {rootPath}");
                                string geminiDir = Path.GetFullPath(Path.Combine(rootPath, "..", "Tools", "omni-gemini-cli")).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                                _logger.LogInformation($"[AiCliService] Resolved geminiDir: {geminiDir}");
                
                                if (!Directory.Exists(geminiDir))                {
                    _logger.LogError($"[AiCliService] Gemini CLI directory NOT FOUND at: {geminiDir}");
                    onProgress?.Invoke($"Error: Gemini CLI directory not found at {geminiDir}");
                    return null;
                }

                string finalWorkspace = "";
                if (string.IsNullOrWhiteSpace(workspace))
                {
                    finalWorkspace = Path.GetFullPath(Path.Combine(rootPath, ".."));
                }
                else
                {
                    finalWorkspace = Path.GetFullPath(workspace);
                }
                finalWorkspace = finalWorkspace.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                string workspaceArg = finalWorkspace.Replace("\\", "/");

                _logger.LogInformation($"[AiCliService] Launching process: cd /d {geminiDir} && node bundle/gemini.js --workspace {workspaceArg}");
                onProgress?.Invoke($"Launching process in {finalWorkspace}...");

                string bundlePath = Path.Combine(geminiDir, "bundle", "gemini.js");
                if (!File.Exists(bundlePath))
                {
                    onProgress?.Invoke("Error: bundle/gemini.js not found.");
                    return null;
                }

                string command = $"title OMNI_GEMINI_INTERACTIVE && cd /d \"{geminiDir}\" && node bundle/gemini.js --workspace \"{workspaceArg}\" --yolo";
                if (!string.IsNullOrEmpty(model))
                {
                    command += $" --model {model}";
                }
                if (!string.IsNullOrEmpty(prepromptFile))
                {
                    command += $" --prepromptfile {prepromptFile.Replace("\\", "/")}";
                }

                string debugLog = Path.Combine(rootPath, "gemini_cli_debug.log");
                string finalCommand = $"set GEMINI_DEBUG_LOG_FILE={debugLog} && {command}";
                
                _hubMonitorService.AddLogMessage($"[AI] Launch Command: {finalCommand}");

                _processService.ExecuteCommandNonAdmin("cmd.exe", $"/K \"{finalCommand}\"");
                
                // We don't have shellProcess.Id with ShellExecute, so we rely on Diff strategy
                _logger.LogInformation($"[AiCliService] Process started via ShellExecute. Waiting for node process via Diff...");
                    onProgress?.Invoke("Process started. Waiting for connection...");

                    int? launchedPid = null;
                    int? candidatePid = null;

                    // Wait for the process and its pipe
                    for (int i = 0; i < 40; i++) 
                    {
                        _logger.LogInformation($"[AiCliService] Launch check iteration {i+1}/40...");
                        await Task.Delay(1000);
                        
                        // Strategy 2: Diff (Essential since we don't have parent PID)
                        _lastWmiDiscovery = DateTime.MinValue;
                        var currentSessions = await DiscoverSessionsAsync(1000, 5000); 
                        var diffPids = currentSessions.Except(initialPids).ToList();
                        
                        if (candidatePid == null && diffPids.Any())
                        {
                            candidatePid = diffPids.First();
                            _logger.LogInformation($"[AiCliService] Identified Candidate PID via Diff: {candidatePid}");
                            launchedPid = candidatePid;
                            break; // EXIT LOOP IMMEDIATELY once we have the PID
                        }
                        
                        onProgress?.Invoke($"Waiting for startup... (Iter {i+1}/40)");
                    }

                    if (launchedPid.HasValue)
                    {
                        int pid = launchedPid.Value;

                        // AUTO-NAME based on workspace (Fast path)
                        try 
                        {
                            string dirName = Path.GetFileName(finalWorkspace.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
                            if (string.IsNullOrEmpty(dirName)) dirName = "Root";
                            await SetSessionNameAsync(pid, GetUniqueSessionName(dirName, pid));
                            _targetPid = pid;
                        }
                        catch {}

                        // NON-BLOCKING Connection, Readiness & Prompt Flushing
                        _ = Task.Run(async () => 
                        {
                            _logger.LogInformation($"[AiCliService] [BG] Starting connection monitor for PID {pid}...");
                            
                            // 1. Wait for pipe connection (Iterative because DiscoverSessionsAsync might have failed it initially)
                            GeminiSession? session = null;
                            for (int retry = 0; retry < 20; retry++)
                            {
                                if (_sessions.TryGetValue(pid, out session) && session.IsConnected)
                                    break;
                                
                                _logger.LogInformation($"[AiCliService] [BG] Waiting for pipe connection for PID {pid} (Attempt {retry+1})...");
                                await DiscoverSessionsAsync(1000, 5000);
                                await Task.Delay(1000);
                            }

                            if (session == null || !session.IsConnected)
                            {
                                _logger.LogError($"[AiCliService] [BG] Failed to establish pipe connection for PID {pid} after retries.");
                                return;
                            }

                            // 2. Flush pending prompts
                            _logger.LogInformation($"[AiCliService] [BG] Processing {_pendingPrompts.Count} pending prompts for PID {pid}...");
                            while (_pendingPrompts.TryDequeue(out var pending))
                            {
                                await _sessionLock.WaitAsync();
                                try 
                                {
                                    string textToSend = pending.Text;
                                    if (_isTriggeringTellPcFromHub && _pendingTellPcContext != null)
                                    {
                                        textToSend = $"[SYSTEM_CONTEXT: {_pendingTellPcContext}]\n\nUser Request: {pending.Text}";
                                        _isTriggeringTellPcFromHub = false;
                                        _pendingTellPcContext = null;
                                    }

                                    _logger.LogInformation($"[AiCliService] [BG] Sending pending prompt directly to PID {pid}: {textToSend.Take(30)}...");
                                    await session.SendPromptAsync(textToSend);
                                }
                                finally
                                {
                                    _sessionLock.Release();
                                }
                            }
                        });

                        return pid;
                    }

                    string errorMsg = "Failed to find new Gemini session after 40 seconds.";
                    _logger.LogWarning($"[AiCliService] {errorMsg}");
                    onProgress?.Invoke(errorMsg);
                    
                    // Cleanup removed: We keep the shell process alive because we used /K 
                    // and we want the user to be able to see any errors in the console.
                    _logger.LogInformation($"[AiCliService][INIT_DEBUG] Launch failed. Leaving shell process alive for user inspection (due to /K).");

                    return null;
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "[AiCliService] Error launching Gemini CLI session");
                    onProgress?.Invoke($"Error launching session: {ex.Message}");
                    return null;
                }
                finally
                {
                    _isLaunching = false;
                }
            }
            finally
            {
                _launchLock.Release();
            }
        }

        public void KillAllGeminiProcesses()
        {
            _logger.LogInformation("[AiCliService] KillAllGeminiProcesses requested.");
            
            // 1. Clear internal state first
            foreach (var session in _sessions.Values)
            {
                session.Dispose();
            }
            _sessions.Clear();
            _sessionNames.Clear();
            _workspaces.Clear();
            _targetPid = -1;
            
            // 2. Kill all node processes running gemini
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
                            int pid = Convert.ToInt32(pidObj);
                            string cmdLower = commandLine.ToLower();
                            bool isGemini = (cmdLower.Contains("bundle/gemini.js") || 
                                             cmdLower.Contains("bundle\\gemini.js") ||
                                             cmdLower.Contains("omni_gemini") || 
                                             (cmdLower.Contains("node") && cmdLower.Contains("gemini.js") && !cmdLower.Contains("@google") && !cmdLower.Contains("node_modules")));

                            if (isGemini)
                            {
                                try
                                {
                                    Process.GetProcessById(pid).Kill(true);
                                    _logger.LogInformation($"[AiCliService] Killed zombie process PID {pid}");
                                }
                                catch (Exception ex)
                                {
                                    _logger.LogWarning($"[AiCliService] Failed to kill zombie process PID {pid}: {ex.Message}");
                                }
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "[AiCliService] Error killing all Gemini processes");
            }
            
            _lastWmiDiscovery = DateTime.MinValue; // Force refresh
        }

        private int? GetNodeProcessIdByParent(int parentPid)
        {
            try
            {
                if (OperatingSystem.IsWindows())
                {
                    string query = $"SELECT ProcessId FROM Win32_Process WHERE ParentProcessId = {parentPid} AND Name LIKE 'node%'";
                    using var searcher = new ManagementObjectSearcher(query);
                    using var collection = searcher.Get();
                    foreach (var process in collection)
                    {
                        return Convert.ToInt32(process["ProcessId"]);
                    }
                }
            }
            catch (Exception ex)
            {
                 _logger.LogDebug($"Error finding child process: {ex.Message}");
            }
            return null;
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

            var session = new GeminiSession(pid, startTime, _logger, _debugMode, (p, text, finished, history, isCodeDiff, isUser) =>
            {
                ResponseReceived?.Invoke(this, new GeminiResponseEventArgs
                {
                    Pid = p,
                    Text = text,
                    IsFinished = finished,
                    IsHistory = history,
                    IsCodeDiff = isCodeDiff,
                    IsUser = isUser
                });
            }, (p, type, prompt, options) =>
            {
                DialogReceived?.Invoke(this, new GeminiDialogEventArgs
                {
                    Pid = p,
                    Type = type,
                    Prompt = prompt,
                    Options = options
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

                        // If this process has been running for a while, it's already ready
                        if ((DateTime.Now - startTime).TotalSeconds > 15)
                        {
                            session.MarkAsReady();
                        }

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

        public async Task<bool> SetTargetPidAsync(int pid)
        {
            _logger.LogInformation($"[AiCliService] Setting target PID to {pid}");
            
            // Ensure we are connected
            if (!_sessions.TryGetValue(pid, out var session) || !session.IsConnected)
            {
                _logger.LogInformation($"[AiCliService] Session {pid} not connected. Attempting immediate connection...");
                await EnsureSessionAsync(pid, 2000);
            }

            bool connected = _sessions.TryGetValue(pid, out var s) && s.IsConnected;
            if (connected)
            {
                _targetPid = pid;
                _logger.LogInformation($"[AiCliService] Successfully targeted and connected to PID {pid}");
            }
            else
            {
                _logger.LogWarning($"[AiCliService] Failed to connect to targeted PID {pid}");
            }
            
            return connected;
        }

        public int GetTargetPid() => _targetPid;

        private async Task<List<int>> GetAllGeminiPidsAsync()
        {
            var now = DateTime.Now;
            if ((now - _lastWmiDiscovery).TotalMinutes < 1 && _cachedWmiPids.Any())
            {
                _logger.LogDebug($"[AiCliService] Using cached Gemini PIDs (Age: {(now - _lastWmiDiscovery).TotalSeconds}s)");
                return _cachedWmiPids;
            }

            var pids = new List<int>();
            try
            {
                if (OperatingSystem.IsWindows())
                {
                    string query = "SELECT ProcessId, CommandLine, ParentProcessId FROM Win32_Process WHERE Name LIKE 'node%'";
                    using var searcher = new ManagementObjectSearcher(query);
                    using var collection = searcher.Get();

                    var rawGeminiProcesses = new List<(int Pid, string Cmd, int Parent)>();

                    foreach (var process in collection)
                    {
                        var commandLine = process["CommandLine"]?.ToString();
                        var pidObj = process["ProcessId"];
                        var parentObj = process["ParentProcessId"];
                        if (commandLine != null && pidObj != null)
                        {
                            int foundPid = Convert.ToInt32(pidObj);
                            int parentPid = parentObj != null ? Convert.ToInt32(parentObj) : 0;
                            string cmdLower = commandLine.ToLower();
                            bool isGemini = (cmdLower.Contains("bundle/gemini.js") || 
                                             cmdLower.Contains("bundle\\gemini.js") ||
                                             cmdLower.Contains("omni_gemini") || 
                                             (cmdLower.Contains("node") && cmdLower.Contains("gemini.js") && !cmdLower.Contains("@google") && !cmdLower.Contains("node_modules")));

                            if (isGemini)
                            {
                                rawGeminiProcesses.Add((foundPid, commandLine, parentPid));

                                // Extract workspace from command line
                                string workspace = "";
                                if (commandLine.Contains("--workspace"))
                                {
                                    var parts = commandLine.Split(new[] { "--workspace" }, StringSplitOptions.None);
                                    if (parts.Length > 1)
                                    {
                                        var wsPath = parts[1].Trim();
                                        if (wsPath.StartsWith("\""))
                                        {
                                            var endQuote = wsPath.IndexOf("\"", 1);
                                            if (endQuote != -1) wsPath = wsPath.Substring(1, endQuote - 1);
                                        }
                                        else if (wsPath.StartsWith("'"))
                                        {
                                            var endQuote = wsPath.IndexOf("'", 1);
                                            if (endQuote != -1) wsPath = wsPath.Substring(1, endQuote - 1);
                                        }
                                        else
                                        {
                                            wsPath = wsPath.Split(' ')[0];
                                        }

                                        try
                                        {
                                            workspace = Path.GetFileName(wsPath.TrimEnd('\\', '/'));
                                        }
                                        catch { workspace = wsPath; }
                                    }
                                }
                                
                                if (!string.IsNullOrEmpty(workspace))
                                {
                                    _workspaces[foundPid] = workspace;
                                }
                            }
                        }
                    }

                    _cachedRawGeminiInfo = rawGeminiProcesses.Select(r => (r.Pid, r.Cmd, r.Parent)).ToList();
                    pids = rawGeminiProcesses.Select(r => r.Pid).ToList();
                    _cachedWmiPids = pids;
                    _lastWmiDiscovery = now;
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "[AiCliService] Error in GetAllGeminiPidsAsync (WMI)");
            }
            return pids;
        }

        public async Task<bool> SendPromptAsync(string text, int pid = -1)
        {
            _logger.LogInformation($"[AiCliService] SendPromptAsync: pid={pid}, _targetPid={_targetPid}, text='{(text.Length > 20 ? text.Substring(0, 20) + "..." : text)}'");
            
            if (_isLaunching)
            {
                _logger.LogInformation("[AiCliService] Launch in progress. Waiting for launch to complete...");
                await _launchLock.WaitAsync();
                _launchLock.Release();
                _logger.LogInformation("[AiCliService] Launch completed. Proceeding with SendPromptAsync.");
            }

            int target = pid;
            if (target == -1) target = _targetPid;

            // FAST PATH: If we already have the session and it's connected, don't run discovery
            if (target != -1 && _sessions.TryGetValue(target, out var fastSession) && fastSession.IsConnected)
            {
                return await SendPromptToSessionAsync(fastSession, text, target);
            }

            // If target is invalid or disconnected, then we run discovery
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

            if (_sessions.TryGetValue(target, out var finalSession))
            {
                return await SendPromptToSessionAsync(finalSession, text, target);
            }

            _logger.LogWarning($"[AiCliService] Session {target} disappeared from dictionary.");
            return false;
        }

        private async Task<bool> SendPromptToSessionAsync(GeminiSession session, string text, int target)
        {
            _logger.LogInformation($"[AiCliService] Sending prompt to PID {target}...");

            string finalPrompt = text;
            if (_tellPcContexts.TryRemove(target, out var context))
            {
                _logger.LogInformation($"[AiCliService] Applying Tell PC context to PID {target}");
                finalPrompt = $"[SYSTEM_CONTEXT: {context}]\n\nUser Request: {text}";
            }
            else if (_isTriggeringTellPcFromHub && _pendingTellPcContext != null)
            {
                _logger.LogInformation($"[AiCliService] Applying PENDING Tell PC context to PID {target}");
                finalPrompt = $"[SYSTEM_CONTEXT: {_pendingTellPcContext}]\n\nUser Request: {text}";
                _isTriggeringTellPcFromHub = false;
                _pendingTellPcContext = null;
            }

            if (text.Contains("I am currently editing the file:"))
            {
                _logger.LogInformation("[AiCliService] SPECIAL: Editing context detected. Setting AI to 'Helper Mode'.");
            }

            // If the session isn't marked ready yet (handshake not received), wait briefly.
            // This ensures we don't spam the pipe before the CLI is fully initialized.
            if (!session.IsReady)
            {
                _logger.LogInformation($"[AiCliService] Session {target} not ready. Waiting for handshake (up to 5s)...");
                await session.WaitUntilReadyAsync(30000);
            }

            await _sessionLock.WaitAsync();
            try
            {
                bool result = await session.SendPromptAsync(finalPrompt);
                _logger.LogInformation($"[AiCliService] SendPromptAsync result for PID {target}: {result}");
                return result;
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

        public async Task<bool> SendDialogResponseAsync(string response, int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target == -1 || !_sessions.TryGetValue(target, out var session) || !session.IsConnected)
            {
                return false;
            }

            return await session.SendDialogResponseAsync(response);
        }

        public async Task GetHistoryAsync(int pid, int maxChars = 0)
        {
            if (_sessions.TryGetValue(pid, out var session))
            {
                if (!session.IsConnected)
                {
                    _logger.LogInformation($"[AiCliService] Session PID {pid} disconnected. Attempting reconnection for history fetch...");
                    bool reconnected = await session.ConnectAsync(2000);
                    if (!reconnected)
                    {
                        _logger.LogWarning($"[AiCliService] Failed to reconnect to session PID {pid} for history fetch.");
                        return;
                    }
                }
                await session.RequestHistoryAsync(maxChars);
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
                _workspaces.TryRemove(target, out _);
                
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
                
                // Invalidate WMI cache to ensure immediate discovery of death
                _lastWmiDiscovery = DateTime.MinValue;
                
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
        private readonly string _sid; // Unique session instance ID
        private readonly DateTime _startTime;
        private readonly ILogger _logger;
        private readonly bool _debugMode;
        private readonly Action<int, string, bool, bool, bool, bool> _onResponse;
        private readonly Action<int, string, string, List<string>?> _onDialog;
        private NamedPipeClientStream? _pipeClient;
        private StreamWriter? _writer;
        private CancellationTokenSource? _cts;
        private readonly StringBuilder _currentResponse = new();
        private readonly HashSet<string> _recentlyBroadcastMessages = new();
        private readonly HashSet<string> _sentPrompts = new();
        private readonly SemaphoreSlim _writeLock = new(1, 1);
        private string? _lastDialogType;
        private Process? _shellProcess;
        private string? _lastHistoryJson;
        private int _pendingMaxChars = 0;
        private bool _isReady = false;
        private readonly TaskCompletionSource<bool> _readyTcs = new();

        public bool IsConnected => _pipeClient?.IsConnected ?? false;
        public bool IsReady => _isReady;
        public int Pid => _pid;
        public string Sid => _sid;
        public DateTime StartTime => _startTime;
        public Process? ShellProcess => _shellProcess;

        public async Task<bool> WaitUntilReadyAsync(int timeoutMs)
        {
            if (_isReady) return true;
            
            var timeoutTask = Task.Delay(timeoutMs);
            var completedTask = await Task.WhenAny(_readyTcs.Task, timeoutTask);
            return completedTask == _readyTcs.Task || _isReady;
        }

        public void MarkAsReady()
        {
            if (!_isReady)
            {
                _isReady = true;
                _readyTcs.TrySetResult(true);
                _logger.LogInformation($"[GeminiSession] SID: {_sid} | Manually marked as READY (e.g. existing session).");
            }
        }

        public GeminiSession(int pid, DateTime startTime, ILogger logger, bool debugMode, Action<int, string, bool, bool, bool, bool> onResponse, Action<int, string, string, List<string>?> onDialog)
        {
            _pid = pid;
            _sid = Guid.NewGuid().ToString().Substring(0, 4);
            _startTime = startTime;
            _logger = logger;
            _debugMode = debugMode;
            _onResponse = onResponse;
            _onDialog = onDialog;
            _logger.LogInformation($"[GeminiSession] Created new session object for PID {_pid} (SID: {_sid})");
        }

        public void SetShellProcess(Process? process)
        {
            _shellProcess = process;
        }

        public async Task<bool> ConnectAsync(int timeoutMs)
        {
            _logger.LogInformation($"[GeminiSession] SID: {_sid} | Attempting connection to pipe 'omni-gemini-cli-{_pid}' (timeout: {timeoutMs}ms)");
            try
            {
                _pipeClient = new NamedPipeClientStream(".", $"omni-gemini-cli-{_pid}", PipeDirection.InOut, PipeOptions.Asynchronous);
                
                await _pipeClient.ConnectAsync(timeoutMs);

                _logger.LogInformation($"[GeminiSession] Pipe connected! Setting up writer and starting read loop.");
                
                _writer = new StreamWriter(_pipeClient) { AutoFlush = true };
                _cts = new CancellationTokenSource();
                _ = Task.Run(() => ReadLoopAsync(_cts.Token));

                _logger.LogInformation($"[GeminiSession] SID: {_sid} | Connected to PID {_pid}");
                return true;
            }
            catch (Exception ex)
            {
                _logger.LogWarning($"[GeminiSession] CONNECTION FAILED for PID {_pid}: {ex.Message} (Type: {ex.GetType().Name})");
                if (ex.InnerException != null)
                {
                    _logger.LogDebug($"[GeminiSession] Inner Exception: {ex.InnerException.Message}");
                }
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

        public async Task<bool> SendDialogResponseAsync(string response)
        {
            _logger.LogDebug($"[GeminiSession] SendDialogResponseAsync to PID {_pid}: {response}");
            return await SendCommandAsync("dialogResponse", response, "response");
        }

                public async Task RequestHistoryAsync(int maxChars = 0)
                {
                    _logger.LogDebug($"[GeminiSession] RequestHistoryAsync to PID {_pid} (maxChars: {maxChars})");
                    _pendingMaxChars = maxChars;
        
                    // Optional: If we have a cached version and maxChars is the same or larger, we could send it instantly
                    // but for now let's always fetch the latest from the CLI to ensure consistency.
                    await SendCommandAsync("getHistory", null, "text", maxChars);
                }
        
                private async Task<bool> SendCommandAsync(string command, string? text, string textPropName = "text", int maxChars = 0)
                {
                    if (!IsConnected || _writer == null) 
                    {
                        _logger.LogWarning($"[GeminiSession] Cannot send command '{command}' to PID {_pid}: Not connected");
                        return false;
                    }
        
                    if (maxChars > 0) _pendingMaxChars = maxChars;
                        
                    await _writeLock.WaitAsync();
                    try
                    {
                        // Normalize path separators in prompt text to avoid double-escaping issues
                        string normalizedText = text?.Replace("\\\\", "/") ?? string.Empty;
                        var payloadObj = new Dictionary<string, object>
                        {
                            { "command", command },
                            { textPropName, normalizedText }
                        };
        
                        if (maxChars > 0)
                        {
                            payloadObj["maxChars"] = maxChars;
                        }
        
                        var payload = JsonSerializer.Serialize(payloadObj);
                                                Console.WriteLine($"[GeminiPipe WRITE] PID {_pid}: {payload}");
                                                _logger.LogInformation($"[GeminiSession] Sending to PID {_pid}: {payload}");
                        
                                                // Add to sent prompts to prevent echo ghosting                        if (command == "prompt" && !string.IsNullOrEmpty(text))
                        {
                            string normalized = text.Trim().ToLower();
                            _logger.LogDebug($"[GeminiSession] Adding to _sentPrompts: {normalized.Take(30)}...");
                            _sentPrompts.Add(normalized);
                        }
        
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
            if (_pipeClient == null) 
            {
                _logger.LogError($"[GeminiSession][INIT_DEBUG] SID: {_sid} | ReadLoopAsync called but _pipeClient is null.");
                return;
            }

            _logger.LogInformation($"[GeminiSession][INIT_DEBUG] SID: {_sid} | Starting read loop for PID {_pid}");
            using var reader = new StreamReader(_pipeClient, Encoding.UTF8, false, 1024, leaveOpen: true);
            try
            {
                while (!token.IsCancellationRequested)
                {
                    _logger.LogDebug($"[GeminiSession] Waiting for line from PID {_pid}...");
                    var line = await reader.ReadLineAsync(token);

                    if (line == null) 
                    {
                        _logger.LogInformation($"[GeminiSession] Read NULL from PID {_pid}. Pipe closed by remote side.");
                        // Small delay to prevent tight loop if Dispose hasn't cleared us yet
                        await Task.Delay(100, token);
                        break;
                    }

                    _logger.LogDebug($"[GeminiSession] SID: {_sid} | Received from PID {_pid}: {line}");
                    
                    try
                    {
                        var msg = JsonDocument.Parse(line);
                        if (msg.RootElement.TryGetProperty("type", out var type))
                        {
                            var typeStr = type.GetString();
                            var text = msg.RootElement.TryGetProperty("text", out var textProp) ? textProp.GetString() : null;

                            // Auto-clear transient status messages like "Authentication in progress..."
                            // when we receive the first real activity after it.
                            if (typeStr == "response" || typeStr == "thought" || typeStr == "codeDiff" || typeStr == "toolCall" || typeStr == "user")
                            {
                                if (_lastDialogType == "auth_in_progress")
                                {
                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Activity received after auth. Clearing auth status for PID {_pid}");
                                    _onResponse(_pid, string.Empty, true, false, false, false); // Sends FINISHED status
                                    _lastDialogType = null;
                                }
                            }

                            if (typeStr == "response")
                            {
                                if (text == null) continue;

                                if (text.Contains("[HISTORY_START]"))
                                {
                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Received history string from PID {_pid}. Length: {text.Length}");
                                    _recentlyBroadcastMessages.Clear(); 
                                    _sentPrompts.Clear();
                                    int startIdx = text.IndexOf("[HISTORY_START]") + "[HISTORY_START]".Length;
                                    int endIdx = text.IndexOf("[HISTORY_END]", startIdx);
                                    if (endIdx != -1)
                                    {
                                        var historyJson = text.Substring(startIdx, endIdx - startIdx);
                                        _logger.LogInformation($"[GeminiSession] SID: {_sid} | Parsed history JSON. Length: {historyJson.Length}");
                                        
                                        // Server-side truncation to prevent client OOM
                                        if (_pendingMaxChars > 0)
                                        {
                                            try
                                            {
                                                var history = JsonSerializer.Deserialize<List<Dictionary<string, string>>>(historyJson);
                                                if (history != null)
                                                {
                                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Deserialized history. Item count: {history.Count}");
                                                    var truncated = new List<Dictionary<string, string>>();
                                                    long currentTotal = 0;
                                                    for (int i = history.Count - 1; i >= 0; i--)
                                                    {
                                                        var item = history[i];
                                                        string itemText = item.ContainsKey("text") ? item["text"] : "";
                                                        if (currentTotal + itemText.Length <= _pendingMaxChars)
                                                        {
                                                            truncated.Insert(0, item);
                                                            currentTotal += itemText.Length;
                                                        }
                                                        else break;
                                                    }
                                                    historyJson = JsonSerializer.Serialize(truncated);
                                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Truncated history to {truncated.Count} items. Final JSON length: {historyJson.Length} (max: {_pendingMaxChars})");
                                                }
                                            }
                                            catch (Exception ex)
                                            {
                                                _logger.LogWarning($"[GeminiSession] SID: {_sid} | Failed to truncate history JSON: {ex.Message}");
                                            }
                                        }

                                        _lastHistoryJson = historyJson;
                                        _onResponse(_pid, historyJson, true, true, false, false);
                                    }
                                    else
                                    {
                                        _logger.LogWarning($"[GeminiSession] SID: {_sid} | Received malformed history (missing [HISTORY_END]) from PID {_pid}");
                                    }
                                }
                                else if (text == "[TURN_FINISHED]")
                                {
                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Turn finished for PID {_pid}");
                                    _recentlyBroadcastMessages.Clear(); 
                                    _sentPrompts.Clear();
                                    _lastDialogType = null;
                                    _onResponse(_pid, string.Empty, true, false, false, false);
                                }
                                else if (text == "[Command Handled]")
                                {
                                    _logger.LogDebug($"[GeminiSession] SID: {_sid} | Command handled for PID {_pid}");
                                }
                                else
                                {
                                    _logger.LogDebug($"[GeminiSession] SID: {_sid} | Processing response from PID {_pid}: {text.Take(30)}...");
                                    // Ghost echo protection: if this matches a prompt we just sent, ignore it
                                    string normalizedText = text.Trim().ToLower();
                                    if (_sentPrompts.Contains(normalizedText))
                                    {
                                        _logger.LogInformation($"[GeminiSession] SID: {_sid} | IGNORED echo from PID {_pid}: {text.Take(30)}...");
                                        continue;
                                    }

                                    if (_recentlyBroadcastMessages.Add(text))
                                    {
                                        _logger.LogDebug($"[GeminiSession] SID: {_sid} | Broadcasting unique response from PID {_pid}");
                                        _onResponse(_pid, text, false, false, false, false);
                                    }
                                    else
                                    {
                                        _logger.LogDebug($"[GeminiSession] SID: {_sid} | Suppressing already broadcast message from PID {_pid}");
                                    }
                                }
                            }
                            else if (typeStr == "user")
                            {
                                if (text != null && _recentlyBroadcastMessages.Add($"user_{text}"))
                                {
                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Received user input from PID {_pid}");
                                    _onResponse(_pid, text, false, false, false, true);
                                }
                            }
                            else if (typeStr == "thought")
                            {
                                if (_recentlyBroadcastMessages.Add($"thought_{text}"))
                                {
                                    _logger.LogDebug($"[GeminiSession] SID: {_sid} | Received thought from PID {_pid}: {text}");
                                    _onResponse(_pid, $"Thinking: {text}", false, false, false, false);
                                }
                            }
                            else if (typeStr == "codeDiff")
                            {
                                if (text != null && _recentlyBroadcastMessages.Add($"diff_{text}"))
                                {
                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Received codeDiff from PID {_pid}");
                                    _onResponse(_pid, text, false, false, true, false);
                                }
                            }
                            else if (typeStr == "toolCall")
                            {
                                if (text != null && _recentlyBroadcastMessages.Add($"tool_{text}"))
                                {
                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Received toolCall from PID {_pid}");
                                    _onResponse(_pid, text, false, false, false, false);
                                }
                            }
                            else if (typeStr == "dialog")
                            {
                                var dialogType = msg.RootElement.TryGetProperty("dialogType", out var dt) ? dt.GetString() : "unknown";
                                var prompt = msg.RootElement.TryGetProperty("prompt", out var pr) ? pr.GetString() : "";
                                var options = msg.RootElement.TryGetProperty("options", out var op) ? 
                                    JsonSerializer.Deserialize<List<string>>(op.GetRawText()) : null;

                                                                _logger.LogInformation($"[GeminiSession] SID: {_sid} | Received dialog from PID {_pid}: {dialogType}");
                                
                                                                if (dialogType == "ready")
                                                                {
                                                                    _isReady = true;
                                                                    _readyTcs.TrySetResult(true);
                                                                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | CLI is READY (handshake received).");
                                                                }
                                _lastDialogType = dialogType;
                                _onDialog(_pid, dialogType ?? "unknown", prompt ?? "", options);
                            }
                        }
                    }
                    catch (JsonException ex)
                    {
                        _logger.LogWarning($"[GeminiSession] SID: {_sid} | Error parsing JSON from PID {_pid}: {ex.Message}. Line: {line}");
                    }
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"[GeminiSession] SID: {_sid} | Read loop error for PID {_pid}");
            }
            finally
            {
                _logger.LogInformation($"[GeminiSession] SID: {_sid} | Read loop finished for PID {_pid}");
            }
        }

        public void Dispose()
        {
            _logger.LogInformation($"[GeminiSession] SID: {_sid} | Dispose called for PID {_pid}");
            _cts?.Cancel();
            _writer?.Dispose();
            _pipeClient?.Dispose();
            _writer = null;
            _pipeClient = null;
            _cts?.Dispose();
            _writeLock.Dispose();

            try
            {
                if (_shellProcess != null && !_shellProcess.HasExited)
                {
                    _shellProcess.Kill(true);
                    _logger.LogInformation($"[GeminiSession] SID: {_sid} | Killed shell process for PID {_pid}");
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning($"[GeminiSession] SID: {_sid} | Could not kill shell process for PID {_pid}: {ex.Message}");
            }
        }
    }
}
