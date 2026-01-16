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

namespace OmniSync.Hub.Infrastructure.Services
{
    public class GeminiResponseEventArgs : EventArgs
    {
        public int Pid { get; set; }
        public string Text { get; set; } = string.Empty;
        public bool IsFinished { get; set; }
        public bool IsHistory { get; set; }
        public bool IsCodeDiff { get; set; }
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
        private int _targetPid = -1;
        private bool _isLaunching = false;
        private readonly SemaphoreSlim _sessionLock = new(1, 1);

        public bool IsBusy => _sessionLock.CurrentCount == 0 || _isLaunching;
        public bool IsDebugModeEnabled => _debugMode;

        public event EventHandler<GeminiResponseEventArgs>? ResponseReceived;
        public event EventHandler<GeminiDialogEventArgs>? DialogReceived;

        public AiCliService(ILogger<AiCliService> logger, HubSettingsService settingsService, ProcessService processService, IConfiguration configuration)
        {
            _logger = logger;
            _settingsService = settingsService;
            _processService = processService;
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
            
            // 1. Try focusing by known terminal title first (most reliable for terminals)
            _processService.WinActivate("OMNI_GEMINI_INTERACTIVE");

            if (_sessions.TryGetValue(pid, out var session))
            {
                // 2. Try to focus the shell process first (the terminal window)
                if (session.ShellProcess != null && !session.ShellProcess.HasExited)
                {
                    _logger.LogInformation($"[AiCliService] Focusing shell process PID {session.ShellProcess.Id}");
                    _processService.WinActivatePid(session.ShellProcess.Id);
                }
                else
                {
                    // 3. Fallback to focusing the node process itself
                    _logger.LogInformation($"[AiCliService] Focusing node process PID {pid}");
                    _processService.WinActivatePid(pid);
                }
            }
            else
            {
                // Last resort: try to focus the PID directly even if not in our sessions list
                _logger.LogWarning($"[AiCliService] Session PID {pid} not found in tracked sessions. Attempting direct focus.");
                _processService.WinActivatePid(pid);
            }

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
                    StartTime = s.StartTime,
                    Workspace = _workspaces.TryGetValue(s.Pid, out var ws) ? ws : string.Empty
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
                                                 cmdLower.Contains("omni_gemini") || 
                                                 (cmdLower.Contains("node") && cmdLower.Contains("gemini") && !cmdLower.Contains("@google") && !cmdLower.Contains("node_modules")));

                                if (isGemini)
                                {
                                    rawGeminiProcesses.Add((foundPid, commandLine, parentPid));
                                }
                            }
                        }

                        // Deduplicate: If a process has a child that is ALSO a Gemini process, 
                        // then this process is likely a wrapper (e.g. cmd.exe or a node parent).
                        // We prefer the 'leaf' process which usually holds the pipe.
                        var parentPids = new HashSet<int>();
                        foreach (var gp in rawGeminiProcesses)
                        {
                            // If this process is a PARENT of another gemini process in our list, skip it (prefer the leaf)
                            if (rawGeminiProcesses.Any(other => other.Parent == gp.Pid))
                            {
                                parentPids.Add(gp.Pid);
                                continue;
                            }

                            // Skip if it's already a connected session
                            if (_sessions.TryGetValue(gp.Pid, out var existing) && existing.IsConnected)
                            {
                                if (!pids.Contains(gp.Pid)) pids.Add(gp.Pid);
                                continue;
                            }

                            // Skip if failed too many times
                            if (_failedPids.TryGetValue(gp.Pid, out var failInfo))
                            {
                                if (failInfo.FailCount >= 3 && (now - failInfo.LastAttempt).TotalMinutes < 1) continue;
                                if ((now - failInfo.LastAttempt).TotalSeconds < 10) continue;
                            }

                            _logger.LogInformation($"[AiCliService] Found verified Gemini process: PID {gp.Pid}");
                            pids.Add(gp.Pid);

                            if (!_sessionNames.ContainsKey(gp.Pid) || !_workspaces.ContainsKey(gp.Pid))
                            {
                                TryExtractWorkspaceAndName(gp.Pid, gp.Cmd);
                            }
                        }
                        
                        // Explicitly remove sessions that we now know are parents (wrappers)
                        foreach (var parentPid in parentPids)
                        {
                            if (_sessions.TryRemove(parentPid, out var session))
                            {
                                _logger.LogInformation($"[AiCliService] Removing wrapper session PID {parentPid} (Leaf process discovered)");
                                session.Dispose();
                                _sessionNames.TryRemove(parentPid, out _);
                                _workspaces.TryRemove(parentPid, out _);
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
                            _logger.LogInformation($"[AiCliService] Removed stale session PID {pid}");
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

            // Clean up stale session names and workspaces for PIDs no longer present
            foreach (var pid in _sessionNames.Keys.ToList())
            {
                if (!pids.Contains(pid))
                {
                    _sessionNames.TryRemove(pid, out _);
                    _workspaces.TryRemove(pid, out _);
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

        public async Task<int?> LaunchSessionAsync(string? workspace = null, Action<string>? onProgress = null)
        {
            if (_isLaunching)
            {
                _logger.LogWarning("[AiCliService] Already launching a session. Ignoring request.");
                onProgress?.Invoke("Already launching a session...");
                return null;
            }

            _isLaunching = true;
            _logger.LogInformation($"[AiCliService] LaunchSessionAsync starting (workspace: {workspace ?? "default"})");
            onProgress?.Invoke("Initializing launch sequence...");
            try
            {
                // Capture ALL existing Gemini PIDs before launch (even if not yet connected)
                onProgress?.Invoke("Scanning for existing sessions...");
                var initialPids = await GetAllGeminiPidsAsync(); 
                _logger.LogInformation($"[AiCliService] Baseline Gemini PIDs: {string.Join(", ", initialPids)}");

                string rootPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", ".."));
                string geminiDir = Path.GetFullPath(Path.Combine(rootPath, "..", "Tools", "gemini-cli"));

                string finalWorkspace = "";
                if (string.IsNullOrWhiteSpace(workspace))
                {
                    finalWorkspace = Path.GetFullPath(Path.Combine(rootPath, ".."));
                }
                else
                {
                    finalWorkspace = Path.GetFullPath(workspace);
                }

                _logger.LogInformation($"[AiCliService] Launching process: cd /d {geminiDir} && node bundle/gemini.js --workspace {finalWorkspace}");
                onProgress?.Invoke($"Launching process in {finalWorkspace}...");

                string command = $"title OMNI_GEMINI_INTERACTIVE && cd /d \"{geminiDir}\" && node bundle/gemini.js --workspace \"{finalWorkspace}\" --yolo";
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
                _logger.LogInformation($"[AiCliService] Process started (Shell PID: {shellProcess.Id}). Waiting for node child process...");
                onProgress?.Invoke("Process started. Waiting for connection...");

                int? launchedPid = null;

                // Wait for the process and its pipe
                for (int i = 0; i < 40; i++) 
                {
                    _logger.LogInformation($"[AiCliService] Launch check iteration {i+1}/40...");
                    await Task.Delay(1000);
                    
                    // Strategy 1: Look for child process (Most robust)
                    int? childPid = GetNodeProcessIdByParent(shellProcess.Id);
                    if (childPid.HasValue)
                    {
                        _logger.LogInformation($"[AiCliService] Found Node child process PID: {childPid.Value}");
                        
                        await EnsureSessionAsync(childPid.Value, 2000);
                        
                        if (_sessions.ContainsKey(childPid.Value))
                        {
                            if (_sessions.TryGetValue(childPid.Value, out var session))
                            {
                                session.SetShellProcess(shellProcess);
                            }
                            launchedPid = childPid.Value;
                            break;
                        }
                    }

                    // Strategy 2: Fallback to Diff
                    _lastWmiDiscovery = DateTime.MinValue;
                    var currentSessions = await DiscoverSessionsAsync(1000, 5000); 
                    var newPid = currentSessions.Except(initialPids).FirstOrDefault();
                    
                    if (newPid != 0)
                    {
                        if (_sessions.TryGetValue(newPid, out var session))
                        {
                            session.SetShellProcess(shellProcess);
                        }
                        launchedPid = newPid;
                        break;
                    }
                    
                    onProgress?.Invoke($"Waiting for startup... (Iter {i+1}/40)");
                }

                if (launchedPid.HasValue)
                {
                    // AUTO-NAME based on workspace
                    try 
                    {
                        string dirName = Path.GetFileName(finalWorkspace.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
                        if (string.IsNullOrEmpty(dirName)) dirName = "Root";
                        
                        await SetSessionNameAsync(launchedPid.Value, GetUniqueSessionName(dirName, launchedPid.Value));
                        
                        // SET TARGET PID to the new session
                        _targetPid = launchedPid.Value;
                        _logger.LogInformation($"[AiCliService] Set target PID to new session {launchedPid.Value}");
                    }
                    catch (Exception ex)
                    {
                        _logger.LogWarning($"Failed to auto-name session for workspace {workspace}: {ex.Message}");
                    }

                    string msg = $"Successfully connected to new session PID {launchedPid.Value}.";
                    onProgress?.Invoke(msg);

                    // Give the CLI a moment to settle (e.g. complete initial auth checks) before dumping the queue
                    // This prevents prompts from being swallowed if the CLI is in an early startup state
                    await Task.Delay(2000);

                    // Process pending prompts if any
                    _logger.LogInformation($"[AiCliService] Processing {_pendingPrompts.Count} pending prompts for PID {launchedPid.Value}...");
                    while (_pendingPrompts.TryDequeue(out var pending))
                    {
                        await _sessionLock.WaitAsync();
                        try 
                        {
                            if (_sessions.TryGetValue(launchedPid.Value, out var session))
                            {
                                string textToSend = pending.Text;
                                if (_isTriggeringTellPcFromHub && _pendingTellPcContext != null)
                                {
                                    _logger.LogInformation($"[AiCliService] Applying PENDING Tell PC context to queued prompt for PID {launchedPid.Value}");
                                    textToSend = $"[SYSTEM_CONTEXT: {_pendingTellPcContext}]\n\nUser Request: {pending.Text}";
                                    _isTriggeringTellPcFromHub = false;
                                    _pendingTellPcContext = null;
                                }

                                _logger.LogInformation($"[AiCliService] Sending pending prompt directly to PID {launchedPid.Value}: {textToSend.Take(30)}...");
                                await session.SendPromptAsync(textToSend);
                            }
                        }
                        finally
                        {
                            _sessionLock.Release();
                        }
                    }

                    return launchedPid.Value;
                }

                string errorMsg = "Failed to find new Gemini session after 40 seconds.";
                _logger.LogWarning($"[AiCliService] {errorMsg}");
                onProgress?.Invoke(errorMsg);
                
                // Cleanup the shell process if we failed to connect
                try
                {
                    if (!shellProcess.HasExited)
                    {
                        shellProcess.Kill(true);
                        _logger.LogInformation($"[AiCliService] Killed shell process {shellProcess.Id} after failed launch.");
                    }
                }
                catch (Exception killEx)
                {
                    _logger.LogWarning($"[AiCliService] Failed to kill shell process after failed launch: {killEx.Message}");
                }

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
                                             cmdLower.Contains("omni_gemini") || 
                                             (cmdLower.Contains("node") && cmdLower.Contains("gemini") && !cmdLower.Contains("@google") && !cmdLower.Contains("node_modules")));

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

            var session = new GeminiSession(pid, startTime, _logger, _debugMode, (p, text, finished, history, isCodeDiff) =>
            {
                ResponseReceived?.Invoke(this, new GeminiResponseEventArgs
                {
                    Pid = p,
                    Text = text,
                    IsFinished = finished,
                    IsHistory = history,
                    IsCodeDiff = isCodeDiff
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
                                             cmdLower.Contains("omni_gemini") || 
                                             (cmdLower.Contains("node") && cmdLower.Contains("gemini") && !cmdLower.Contains("@google") && !cmdLower.Contains("node_modules")));

                            if (isGemini)
                            {
                                rawGeminiProcesses.Add((foundPid, commandLine, parentPid));
                            }
                        }
                    }

                    // Deduplicate: If a process has a child that is ALSO a Gemini process, skip the parent.
                    foreach (var gp in rawGeminiProcesses)
                    {
                        if (rawGeminiProcesses.Any(other => other.Parent == gp.Pid))
                        {
                            continue;
                        }
                        pids.Add(gp.Pid);
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "[AiCliService] Error getting all Gemini PIDs");
            }
            return pids;
        }

        public async Task<bool> SendPromptAsync(string text, int pid = -1)
        {
            _logger.LogInformation($"[AiCliService] SendPromptAsync: pid={pid}, _targetPid={_targetPid}, text='{(text.Length > 20 ? text.Substring(0, 20) + "..." : text)}'");
            
            if (_isLaunching)
            {
                _logger.LogInformation("[AiCliService] Launch in progress. Queuing prompt.");
                _pendingPrompts.Enqueue((text, pid));
                return true; 
            }

            int target = pid;

            if (target == -1) target = _targetPid;

            // If target is STILL -1 or session not found, we might have a race condition where the session just finished launching
            // but isn't in the dictionary yet. However, _isLaunching should have caught that.
            // One possibility: if pid was -1, but _targetPid is still -1.
            
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

            string finalPrompt = text;
            if (_tellPcContexts.TryRemove(target, out var context))
            {
                _logger.LogInformation($"[AiCliService] Applying Tell PC context to PID {target}");
                finalPrompt = $"[SYSTEM_CONTEXT: {context}]\n\nUser Request: {text}";
            }
            else if (_isTriggeringTellPcFromHub && _pendingTellPcContext != null)
            {
                // This handles the case where the message arrives before the PID was known during Tell PC flow
                _logger.LogInformation($"[AiCliService] Applying PENDING Tell PC context to PID {target}");
                finalPrompt = $"[SYSTEM_CONTEXT: {_pendingTellPcContext}]\n\nUser Request: {text}";
                _isTriggeringTellPcFromHub = false;
                _pendingTellPcContext = null;
            }

            if (text.Contains("I am currently editing the file:"))
            {
                _logger.LogInformation("[AiCliService] SPECIAL: Editing context detected. Setting AI to 'Helper Mode'.");
            }
            await _sessionLock.WaitAsync();
            try
            {
                if (_sessions.TryGetValue(target, out var session))
                {
                    bool result = await session.SendPromptAsync(finalPrompt);
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

        public async Task<bool> SendDialogResponseAsync(string response, int pid = -1)
        {
            int target = pid == -1 ? _targetPid : pid;
            if (target == -1 || !_sessions.TryGetValue(target, out var session) || !session.IsConnected)
            {
                return false;
            }

            return await session.SendDialogResponseAsync(response);
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
        private readonly DateTime _startTime;
        private readonly ILogger _logger;
        private readonly bool _debugMode;
        private readonly Action<int, string, bool, bool, bool> _onResponse;
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

        public bool IsConnected => _pipeClient?.IsConnected ?? false;
        public int Pid => _pid;
        public DateTime StartTime => _startTime;
        public Process? ShellProcess => _shellProcess;

        public GeminiSession(int pid, DateTime startTime, ILogger logger, bool debugMode, Action<int, string, bool, bool, bool> onResponse, Action<int, string, string, List<string>?> onDialog)
        {
            _pid = pid;
            _startTime = startTime;
            _logger = logger;
            _debugMode = debugMode;
            _onResponse = onResponse;
            _onDialog = onDialog;
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
                _logger.LogWarning($"[GeminiSession] Could not connect to pipe for PID {_pid} within {timeoutMs}ms: {ex.Message} (Type: {ex.GetType().Name})");
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

        public async Task RequestHistoryAsync()
        {
            _logger.LogDebug($"[GeminiSession] RequestHistoryAsync to PID {_pid}");
            await SendCommandAsync("getHistory", null);
        }

        private async Task<bool> SendCommandAsync(string command, string? text, string textPropName = "text")
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
                                var payloadObj = new Dictionary<string, object>
                                {
                                    { "command", command },
                                    { textPropName, normalizedText }
                                };
                                var payload = JsonSerializer.Serialize(payloadObj);
                                Console.WriteLine($"[GeminiPipe WRITE] PID {_pid}: {payload}");
                                _logger.LogInformation($"[GeminiSession] Sending to PID {_pid}: {payload}");

                                // Add to sent prompts to prevent echo ghosting
                                if (command == "prompt" && !string.IsNullOrEmpty(text))
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

                    if (_debugMode)
                    {
                        _logger.LogInformation($"[GeminiPipe DEBUG] PID {_pid} RAW: {line}");
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

                            // Auto-clear transient status messages like "Authentication in progress..."
                            // when we receive the first real activity after it.
                            if (typeStr == "response" || typeStr == "thought" || typeStr == "codeDiff" || typeStr == "toolCall")
                            {
                                if (_lastDialogType == "auth_in_progress")
                                {
                                    _logger.LogInformation($"[GeminiSession] Activity received after auth. Clearing auth status for PID {_pid}");
                                    _onResponse(_pid, string.Empty, true, false, false); // Sends FINISHED status
                                    _lastDialogType = null;
                                }
                            }

                            if (typeStr == "response")
                            {
                                if (text == null) continue;

                                if (text.Contains("[HISTORY_START]"))
                                {
                                    _logger.LogInformation($"[GeminiSession] Received history from PID {_pid}");
                                    _recentlyBroadcastMessages.Clear(); 
                                    _sentPrompts.Clear();
                                    int startIdx = text.IndexOf("[HISTORY_START]") + "[HISTORY_START]".Length;
                                    int endIdx = text.IndexOf("[HISTORY_END]", startIdx);
                                    if (endIdx != -1)
                                    {
                                        var historyJson = text.Substring(startIdx, endIdx - startIdx);
                                        _onResponse(_pid, historyJson, true, true, false);
                                    }
                                }
                                else if (text == "[TURN_FINISHED]")
                                {
                                    _logger.LogInformation($"[GeminiSession] Turn finished for PID {_pid}");
                                    _recentlyBroadcastMessages.Clear(); 
                                    _sentPrompts.Clear();
                                    _lastDialogType = null;
                                    _onResponse(_pid, string.Empty, true, false, false);
                                }
                                else if (text == "[Command Handled]")
                                {
                                    _logger.LogDebug($"[GeminiSession] Command handled for PID {_pid}");
                                }
                                else
                                {
                                    _logger.LogDebug($"[GeminiSession] Processing response from PID {_pid}: {text.Take(30)}...");
                                    // Ghost echo protection: if this matches a prompt we just sent, ignore it
                                    string normalizedText = text.Trim().ToLower();
                                    if (_sentPrompts.Contains(normalizedText))
                                    {
                                        _logger.LogInformation($"[GeminiSession] IGNORED echo from PID {_pid}: {text.Take(30)}...");
                                        continue;
                                    }

                                    if (_recentlyBroadcastMessages.Add(text))
                                    {
                                        _logger.LogDebug($"[GeminiSession] Broadcasting unique response from PID {_pid}");
                                        _onResponse(_pid, text, false, false, false);
                                    }
                                    else
                                    {
                                        _logger.LogDebug($"[GeminiSession] Suppressing already broadcast message from PID {_pid}");
                                    }
                                }
                            }
                            else if (typeStr == "thought")
                            {
                                if (_recentlyBroadcastMessages.Add($"thought_{text}"))
                                {
                                    _logger.LogDebug($"[GeminiSession] Received thought from PID {_pid}: {text}");
                                    _onResponse(_pid, $"Thinking: {text}", false, false, false);
                                }
                            }
                            else if (typeStr == "codeDiff")
                            {
                                if (text != null && _recentlyBroadcastMessages.Add($"diff_{text}"))
                                {
                                    _logger.LogInformation($"[GeminiSession] Received codeDiff from PID {_pid}");
                                    _onResponse(_pid, text, false, false, true);
                                }
                            }
                            else if (typeStr == "toolCall")
                            {
                                if (text != null && _recentlyBroadcastMessages.Add($"tool_{text}"))
                                {
                                    _logger.LogInformation($"[GeminiSession] Received toolCall from PID {_pid}");
                                    _onResponse(_pid, text, false, false, false);
                                }
                            }
                            else if (typeStr == "dialog")
                            {
                                var dialogType = msg.RootElement.TryGetProperty("dialogType", out var dt) ? dt.GetString() : "unknown";
                                var prompt = msg.RootElement.TryGetProperty("prompt", out var pr) ? pr.GetString() : "";
                                var options = msg.RootElement.TryGetProperty("options", out var op) ? 
                                    JsonSerializer.Deserialize<List<string>>(op.GetRawText()) : null;

                                _logger.LogInformation($"[GeminiSession] Received dialog from PID {_pid}: {dialogType}");
                                _lastDialogType = dialogType;
                                _onDialog(_pid, dialogType ?? "unknown", prompt ?? "", options);
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