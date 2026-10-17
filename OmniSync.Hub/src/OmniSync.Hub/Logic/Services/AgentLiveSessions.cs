using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Management;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services
{
    /// <summary>A Claude Code process that currently holds a session open (reported by OmniAgentHook).</summary>
    /// <param name="Busy">Whether a turn is running; null when only the process is known (no hook report yet).</param>
    public sealed record AgentLiveClient(string SessionId, int Pid, string Client, bool? Busy, DateTime UpdatedAt);

    /// <summary>
    /// Delivery into sessions that are open in another client, without forking them:
    ///  - terminal clients: OmniAgentHook types the message into the agent's console;
    ///  - Claude desktop app / any Claude Code: messages go to ~/.omni/agent-inbox/&lt;session&gt;.jsonl and the
    ///    OmniAgentHook Claude Code hook hands them to the model after the next tool call, at turn end, or with
    ///    the user's next prompt.
    /// Also installs that hook into ~/.claude/settings.json.
    /// </summary>
    public sealed class AgentLiveSessions
    {
        private static readonly string OmniDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".omni");
        private static readonly string LiveDir = Path.Combine(OmniDir, "agent-live");
        private static readonly string InboxDir = Path.Combine(OmniDir, "agent-inbox");
        public static readonly string HookExe = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Omni", "AgentHook", "OmniAgentHook.exe");
        private static readonly string[] HookEvents = { "SessionStart", "SessionEnd", "UserPromptSubmit", "PostToolUse", "Stop" };

        private readonly ILogger _logger;
        private Dictionary<string, AgentLiveClient> _snapshot = new();
        private DateTime _snapshotAt = DateTime.MinValue;
        private readonly object _gate = new();

        public AgentLiveSessions(ILogger logger)
        {
            _logger = logger;
        }

        public bool HookAvailable => File.Exists(HookExe);

        /// <summary>The most relevant live client per session id (app/terminal before Omni's own headless runs).</summary>
        public AgentLiveClient? Find(string sessionId)
        {
            if (string.IsNullOrWhiteSpace(sessionId)) return null;
            lock (_gate)
            {
                if (DateTime.UtcNow - _snapshotAt > TimeSpan.FromSeconds(3)) Refresh();
                return _snapshot.TryGetValue(sessionId, out var client) ? client : null;
            }
        }

        private static readonly Regex SessionArg = new(@"(?:--resume[= ]|--session-id[= ])([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})", RegexOptions.Compiled);

        private void Refresh()
        {
            var result = new Dictionary<string, AgentLiveClient>();
            // Primary signal: scan running claude.exe processes. Reliable even if a session's hooks never fired,
            // which happens for sessions started before OmniAgentHook was installed.
            try
            {
                using var searcher = new ManagementObjectSearcher("SELECT ProcessId, ParentProcessId, Name, CommandLine FROM Win32_Process WHERE Name = 'claude.exe'");
                var all = new Dictionary<int, (int Parent, string Name, string Cmd)>();
                foreach (ManagementObject obj in searcher.Get())
                {
                    using (obj)
                        all[Convert.ToInt32(obj["ProcessId"])] = (
                            Convert.ToInt32(obj["ParentProcessId"]),
                            (string)(obj["Name"] ?? ""),
                            (string)(obj["CommandLine"] ?? ""));
                }
                foreach (var (pid, info) in all)
                {
                    var match = SessionArg.Match(info.Cmd);
                    if (!match.Success) continue;
                    var sessionId = match.Groups[1].Value.ToLowerInvariant();
                    var parentCmd = all.TryGetValue(info.Parent, out var parent) ? parent.Cmd : "";
                    var parentName = ParentImageName(info.Parent);
                    var client = (parentName.Contains("Hub", StringComparison.OrdinalIgnoreCase)
                                  || parentCmd.Contains("OmniSync.Hub", StringComparison.OrdinalIgnoreCase)) ? "omni"
                        : parentName.StartsWith("claude", StringComparison.OrdinalIgnoreCase) ? "app"
                        : "terminal";
                    if (client == "omni") continue;
                    // The process alone says nothing about busy/idle: the desktop app (--input-format stream-json) and
                    // interactive terminals keep claude.exe alive between turns. Only a --print run exits with its turn.
                    bool? busy = Regex.IsMatch(info.Cmd, @"(^|\s)(--print|-p)(\s|$)") ? true : null;
                    var entry = new AgentLiveClient(sessionId, pid, client, busy, UpdatedAt: DateTime.MinValue);
                    if (!result.TryGetValue(sessionId, out var existing) || existing.Client == "unknown") result[sessionId] = entry;
                }
            }
            catch (Exception error) { _logger.LogDebug(error, "[AgentLive] WMI scan failed"); }

            // Hook reports are authoritative for busy/idle and replace the process-scan guess above.
            var reported = new Dictionary<string, AgentLiveClient>();
            if (Directory.Exists(LiveDir))
            {
                foreach (var file in Directory.EnumerateFiles(LiveDir, "*.json"))
                {
                    try
                    {
                        using var doc = JsonDocument.Parse(File.ReadAllText(file));
                        var r = doc.RootElement;
                        var client = new AgentLiveClient(
                            r.GetProperty("SessionId").GetString() ?? "",
                            r.GetProperty("Pid").GetInt32(),
                            r.GetProperty("Client").GetString() ?? "unknown",
                            r.GetProperty("Busy").GetBoolean(),
                            DateTime.TryParse(r.GetProperty("UpdatedAt").GetString(), null, System.Globalization.DateTimeStyles.AdjustToUniversal, out var t) ? t : DateTime.MinValue);
                        if (!IsClaudeProcess(client.Pid))
                        {
                            File.Delete(file); // the client closed or crashed without SessionEnd
                            continue;
                        }
                        if (client.Client == "omni") continue; // Omni's own headless turns are tracked by AgentSessionService
                        if (!reported.TryGetValue(client.SessionId, out var existing) || client.UpdatedAt > existing.UpdatedAt)
                            reported[client.SessionId] = client;
                    }
                    catch (Exception error) when (error is IOException or JsonException or KeyNotFoundException or InvalidOperationException)
                    {
                        // Being rewritten right now; the next refresh picks it up.
                    }
                }
            }
            foreach (var (sessionId, client) in reported) result[sessionId] = client;
            _snapshot = result;
            _snapshotAt = DateTime.UtcNow;
        }

        private static string ParentImageName(int pid)
        {
            try { using var p = Process.GetProcessById(pid); return p.ProcessName; }
            catch { return ""; }
        }

        private static bool IsClaudeProcess(int pid)
        {
            if (pid <= 0) return false;
            try
            {
                using var p = Process.GetProcessById(pid);
                return !p.HasExited && p.ProcessName.StartsWith("claude", StringComparison.OrdinalIgnoreCase);
            }
            catch (ArgumentException) { return false; }
            catch (InvalidOperationException) { return false; }
        }

        // ---------- delivery ----------

        public sealed record InboxMessage(string Id, string Text, string At);

        public void Enqueue(string sessionId, string text)
        {
            Directory.CreateDirectory(InboxDir);
            var line = JsonSerializer.Serialize(new { id = Guid.NewGuid().ToString("N")[..10], text, at = DateTime.UtcNow.ToString("o") });
            File.AppendAllText(Path.Combine(InboxDir, sessionId + ".jsonl"), line + "\n", new UTF8Encoding(false));
        }

        /// <summary>Messages still waiting for the hook to pick them up.</summary>
        public List<InboxMessage> Pending(string sessionId)
        {
            var path = Path.Combine(InboxDir, sessionId + ".jsonl");
            var result = new List<InboxMessage>();
            if (!File.Exists(path)) return result;
            try
            {
                foreach (var line in File.ReadAllLines(path))
                {
                    if (line.Trim().Length == 0) continue;
                    using var doc = JsonDocument.Parse(line);
                    var r = doc.RootElement;
                    result.Add(new InboxMessage(r.GetProperty("id").GetString() ?? "", r.GetProperty("text").GetString() ?? "", r.GetProperty("at").GetString() ?? ""));
                }
            }
            catch (Exception error) when (error is IOException or JsonException) { }
            return result;
        }

        public bool Cancel(string sessionId, string messageId)
        {
            var path = Path.Combine(InboxDir, sessionId + ".jsonl");
            try
            {
                var keep = Pending(sessionId).Where(m => m.Id != messageId).ToList();
                if (keep.Count == Pending(sessionId).Count) return false;
                if (keep.Count == 0) File.Delete(path);
                else File.WriteAllLines(path, keep.Select(m => JsonSerializer.Serialize(new { id = m.Id, text = m.Text, at = m.At })), new UTF8Encoding(false));
                return true;
            }
            catch (IOException) { return false; } // the hook claimed it a moment ago
        }

        /// <summary>Types the message into the console of a terminal-hosted agent, as if the user typed it.</summary>
        public async Task TypeIntoConsoleAsync(int pid, string text)
        {
            if (!HookAvailable) throw new InvalidOperationException("OmniAgentHook is not installed on the PC.");
            var psi = new ProcessStartInfo(HookExe, $"inject {pid} {Convert.ToBase64String(Encoding.UTF8.GetBytes(text))}")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
            };
            using var process = Process.Start(psi) ?? throw new InvalidOperationException("Could not start OmniAgentHook.");
            var stderr = await process.StandardError.ReadToEndAsync();
            await process.WaitForExitAsync();
            if (process.ExitCode != 0)
                throw new InvalidOperationException("Could not type into the terminal: " + stderr.Trim());
        }

        // ---------- hook installation ----------

        /// <summary>
        /// The hook command line, runnable by every shell Claude Code may use for hooks (Git Bash, cmd, PowerShell):
        /// an unquoted path, since PowerShell treats a leading quoted string as a value and needs `&amp;` to call it.
        /// A path with spaces falls back to its 8.3 short form.
        /// </summary>
        private static string HookCommand()
        {
            var path = HookExe;
            if (path.Contains(' '))
            {
                var buffer = new StringBuilder(1024);
                if (GetShortPathName(path, buffer, buffer.Capacity) > 0) path = buffer.ToString();
            }
            path = path.Replace('\\', '/');
            return (path.Contains(' ') ? "\"" + path + "\"" : path) + " hook";
        }

        [System.Runtime.InteropServices.DllImport("kernel32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
        private static extern uint GetShortPathName(string longPath, StringBuilder shortPath, int bufferLength);

        /// <summary>Adds the OmniAgentHook entries to ~/.claude/settings.json (idempotent; keeps a one-time backup).</summary>
        public void EnsureClaudeHooksInstalled()
        {
            if (!HookAvailable)
            {
                _logger.LogInformation("[AgentLive] OmniAgentHook not published at {Path}; live-session delivery for Claude is off", HookExe);
                return;
            }
            var settingsPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".claude", "settings.json");
            try
            {
                var root = File.Exists(settingsPath)
                    ? JsonNode.Parse(File.ReadAllText(settingsPath)) as JsonObject ?? new JsonObject()
                    : new JsonObject();
                var hooks = root["hooks"] as JsonObject;
                if (hooks is null) root["hooks"] = hooks = new JsonObject();
                var command = HookCommand();
                var changed = false;
                foreach (var evt in HookEvents)
                {
                    var list = hooks[evt] as JsonArray;
                    if (list is null) hooks[evt] = list = new JsonArray();
                    var existing = list
                        .SelectMany(entry => entry?["hooks"] as JsonArray ?? new JsonArray())
                        .OfType<JsonObject>()
                        .Where(hook => (hook["command"]?.GetValue<string>() ?? "").Contains("OmniAgentHook", StringComparison.OrdinalIgnoreCase))
                        .ToList();
                    foreach (var hook in existing.Where(hook => hook["command"]?.GetValue<string>() != command))
                    {
                        hook["command"] = command; // older installs wrote a quoted path that PowerShell cannot run
                        changed = true;
                    }
                    if (existing.Count > 0) continue;
                    var entry = new JsonObject
                    {
                        ["hooks"] = new JsonArray(new JsonObject { ["type"] = "command", ["command"] = command, ["timeout"] = 10 }),
                    };
                    if (evt == "PostToolUse") entry["matcher"] = "*";
                    list.Add(entry);
                    changed = true;
                }
                if (!changed) return;
                var backup = settingsPath + ".before-omni-hooks";
                if (File.Exists(settingsPath) && !File.Exists(backup)) File.Copy(settingsPath, backup);
                Directory.CreateDirectory(Path.GetDirectoryName(settingsPath)!);
                File.WriteAllText(settingsPath, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }), new UTF8Encoding(false));
                _logger.LogInformation("[AgentLive] Installed OmniAgentHook into {Path} (backup {Backup})", settingsPath, backup);
            }
            catch (Exception error)
            {
                _logger.LogWarning(error, "[AgentLive] Could not install Claude hooks into {Path}", settingsPath);
            }
        }
    }
}
