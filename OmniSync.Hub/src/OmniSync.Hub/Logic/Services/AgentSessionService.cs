using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Models;

namespace OmniSync.Hub.Logic.Services
{
    /// <summary>
    /// Read-only discovery and normalization of local Codex, Claude Code and Antigravity
    /// transcripts (port of artifacts/agent-reader/agent_session_reader.py).
    /// Sessions started outside the Hub appear as view-only entries (Control = false).
    /// </summary>
    public class AgentSessionService
    {
        private const int MaxCachedEvents = 2000;
        private const int ListingPerSource = 40;
        private static readonly TimeSpan RescanInterval = TimeSpan.FromSeconds(15);
        private static readonly string[] TransportEnvelopeMarkers =
        {
            "<recommended_plugins>",
            "<environment_context>",
            "<in-app-browser-context",
            "<skills_instructions>",
            "<permissions instructions>",
        };
        private static readonly string[] TitleKeys =
        {
            "customTitle", "custom_title", "conversationTitle", "conversation_title",
            "sessionTitle", "session_title", "sessionName", "session_name", "title",
        };
        private static readonly Regex UserRequestRegex = new("<USER_REQUEST>\\s*(.*?)\\s*</USER_REQUEST>", RegexOptions.Compiled | RegexOptions.Singleline);
        private static readonly Regex TagBlockRegex = new("<[A-Z][A-Z0-9_]*>.*?</[A-Z][A-Z0-9_]*>", RegexOptions.Compiled | RegexOptions.Singleline);
        private static readonly Regex MyRequestRegex = new("##\\s*My request:\\s*([\\s\\S]*)", RegexOptions.Compiled | RegexOptions.IgnoreCase);
        private static readonly Regex SentenceBoundaryRegex = new("(?<=[.!?])\\s", RegexOptions.Compiled);
        private static readonly Regex ToolErrorRegex = new("\\b(error|failed|exception|denied|forbidden|exit code [1-9])\\b", RegexOptions.Compiled | RegexOptions.IgnoreCase);

        private readonly ILogger<AgentSessionService> _logger;
        private readonly object _gate = new();
        private DateTime _lastScan = DateTime.MinValue;
        private Dictionary<string, (string Provider, string[] Paths)> _entries = new();
        private readonly Dictionary<string, (string Version, AgentSessionDetail Detail)> _cache = new();
        private readonly Dictionary<string, (string Version, string Title)> _listCache = new();

        public AgentSessionService(ILogger<AgentSessionService> logger)
        {
            _logger = logger;
        }

        private static string Home => Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

        // ---------- discovery ----------

        public List<AgentSessionSummary> Listing()
        {
            lock (_gate) Discover();

            var result = new List<AgentSessionSummary>();
            foreach (var (key, entry) in _entries)
            {
                try
                {
                    var stats = entry.Paths.Select(p => new FileInfo(p)).ToList();
                    var newest = stats.OrderByDescending(s => s.LastWriteTimeUtc.Ticks).First();
                    var version = VersionOf(stats);
                    string title;
                    lock (_gate) title = ListingTitle(key, entry.Provider, entry.Paths, version);
                    result.Add(new AgentSessionSummary
                    {
                        Id = key,
                        Provider = entry.Provider,
                        Title = title,
                        Modified = newest.LastWriteTimeUtc.ToString("o"),
                        Cwd = "",
                        Control = false,
                    });
                }
                catch (Exception error)
                {
                    _logger.LogWarning(error, "[AgentSessions] listing failed for {Key}", key);
                }
            }
            return result.OrderByDescending(s => s.Modified).ToList();
        }

        public AgentSessionDetail Read(string key, int limit = 120, int before = 0)
        {
            (string Provider, string[] Paths) entry;
            lock (_gate)
            {
                Discover();
                if (!_entries.TryGetValue(key, out entry!))
                    throw new FileNotFoundException($"Unknown agent session {key}");
            }

            var stats = entry.Paths.Select(p => new FileInfo(p)).ToList();
            var version = VersionOf(stats);
            lock (_gate)
            {
                if (_cache.TryGetValue(key, out var cached) && cached.Version == version)
                    return Page(cached.Detail, limit, before);
            }

            var detail = ParseSession(entry.Provider, entry.Paths, stats);
            detail.Id = key;
            lock (_gate) _cache[key] = (version, detail);
            return Page(detail, limit, before);
        }

        /// <summary>
        /// Send one user message to a discovered session by resuming it through the
        /// provider's headless CLI (the mechanism proven by the agent-reader roundtrips).
        /// Returns once the turn finished; the transcript file update is then picked up
        /// by the next discovery scan.
        /// </summary>
        public async Task SendToSessionAsync(string key, string message)
        {
            (string Provider, string[] Paths) entry;
            string sessionId, cwd;
            lock (_gate)
            {
                Discover();
                if (!_entries.TryGetValue(key, out entry!))
                    throw new FileNotFoundException($"Unknown agent session {key}");
            }
            var stats = entry.Paths.Select(p => new FileInfo(p)).ToList();
            var version = VersionOf(stats);
            AgentSessionDetail snapshot;
            lock (_gate)
            {
                if (_cache.TryGetValue(key, out var cached) && cached.Version == version)
                    snapshot = cached.Detail;
                else
                {
                    snapshot = ParseSession(entry.Provider, entry.Paths, stats);
                    snapshot.Id = key;
                    _cache[key] = (version, snapshot);
                }
            }
            sessionId = snapshot.SessionId;
            cwd = snapshot.Cwd;
            if (string.IsNullOrWhiteSpace(sessionId))
                throw new InvalidOperationException("Session has no resumable id yet.");

            var (fileName, arguments) = BuildResumeCommand(entry.Provider, sessionId);
            if (fileName is null)
                throw new NotSupportedException($"Sending to {entry.Provider} is not supported yet.");

            var scratch = Path.Combine(Path.GetTempPath(), "omni-agent-send");
            Directory.CreateDirectory(scratch);

            var psi = new System.Diagnostics.ProcessStartInfo
            {
                FileName = fileName,
                Arguments = arguments,
                WorkingDirectory = string.IsNullOrWhiteSpace(cwd) ? scratch : cwd,
                UseShellExecute = false,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            };
            if (entry.Provider == "Antigravity")
            {
                // agy takes the prompt as an argument; the others read stdin.
                psi.Arguments = arguments + " " + Quote(message);
            }

            _logger.LogInformation("[AgentSessions] Send to {Provider} session {SessionId}", entry.Provider, sessionId);
            using var process = System.Diagnostics.Process.Start(psi)
                ?? throw new InvalidOperationException($"Failed to launch {fileName}");
            if (entry.Provider != "Antigravity")
                await process.StandardInput.WriteAsync(message);
            process.StandardInput.Close();

            var stdoutTask = process.StandardOutput.ReadToEndAsync();
            var stderrTask = process.StandardError.ReadToEndAsync();
            var exitTask = process.WaitForExitAsync();
            var completed = await Task.WhenAny(exitTask, Task.Delay(TimeSpan.FromSeconds(180)));
            if (completed != exitTask)
            {
                // Timeout: kill the process tree so no orphan CLI remains.
                try { process.Kill(entireProcessTree: true); } catch (Exception) { }
                throw new TimeoutException($"{entry.Provider} turn timed out after 180s");
            }
            var stdout = await stdoutTask;
            var stderr = await stderrTask;
            if (process.ExitCode != 0)
                throw new InvalidOperationException($"{entry.Provider} turn failed (exit {process.ExitCode}): {Truncate(stderr, 500)}");
            _logger.LogInformation("[AgentSessions] Send completed for {SessionId} ({Bytes} bytes stdout)", sessionId, stdout.Length);
        }

        private static string Quote(string value) => "\"" + value.Replace("\"", "\\\"") + "\"";

        private static string Truncate(string value, int max) =>
            string.IsNullOrEmpty(value) || value.Length <= max ? value : value[..max];

        private static (string? FileName, string Arguments) BuildResumeCommand(string provider, string sessionId)
        {
            var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var userProfile = Home;
            switch (provider)
            {
                case "Codex":
                {
                    var launcher = Path.Combine(appData, "npm", "node_modules", "@openai", "codex", "bin", "codex.js");
                    if (!File.Exists(launcher)) return (null, "");
                    return ("node",
                        Quote(launcher) + " exec --ignore-user-config --sandbox read-only --skip-git-repo-check --json resume " + Quote(sessionId) + " -");
                }
                case "Claude":
                {
                    var executable = Path.Combine(userProfile, ".local", "bin", "claude.exe");
                    if (!File.Exists(executable)) return (null, "");
                    return (executable,
                        "--print --output-format json --tools \"\" --strict-mcp-config --settings \"{\\\"disableAllHooks\\\":true}\" --effort low --resume " + Quote(sessionId));
                }
                case "Antigravity":
                {
                    var executable = Path.Combine(localAppData, "agy", "bin", "agy.exe");
                    if (!File.Exists(executable)) return (null, "");
                    return (executable,
                        "--print --output-format json --sandbox --mode plan --effort low --print-timeout 90s --conversation " + Quote(sessionId));
                }
                default:
                    return (null, "");
            }
        }

        private void Discover()
        {
            if (DateTime.UtcNow - _lastScan < RescanInterval && _entries.Count > 0) return;
            var entries = new Dictionary<string, (string, string[])>();
            var sources = new List<(string Provider, string Root, int Depth, bool IsCodex)>
            {
                ("Codex", Path.Combine(Home, ".codex", "sessions"), 4, true),
                ("Claude", Path.Combine(Home, ".claude", "projects"), 2, false),
                ("Antigravity", Path.Combine(Home, ".gemini", "antigravity-cli", "brain"), 0, false),
                ("Antigravity", Path.Combine(Home, ".gemini", "antigravity", "brain"), 0, false),
            };

            foreach (var (provider, root, depth, isCodex) in sources)
            {
                List<string> recent;
                try
                {
                    if (!Directory.Exists(root)) continue;
                    if (depth == 0)
                    {
                        // Antigravity: brain/<id>/.system_generated/logs/transcript.jsonl
                        recent = Directory.EnumerateDirectories(root)
                            .Select(id => Path.Combine(id, ".system_generated", "logs", "transcript.jsonl"))
                            .Where(File.Exists)
                            .OrderByDescending(File.GetLastWriteTimeUtc)
                            .Take(ListingPerSource)
                            .ToList();
                    }
                    else
                    {
                        recent = Directory.EnumerateFiles(root, "*.jsonl", SearchOption.AllDirectories)
                            .Where(p => RelativeDepth(root, p) == depth)
                            .OrderByDescending(File.GetLastWriteTimeUtc)
                            .Take(ListingPerSource)
                            .ToList();
                    }
                }
                catch (Exception error)
                {
                    _logger.LogWarning(error, "[AgentSessions] discovery failed under {Root}", root);
                    continue;
                }

                if (isCodex)
                {
                    // Fragments of one thread can live in different date folders; group by
                    // thread-id substring in the file name, newest fragment keeps the header.
                    var allFragments = Directory.EnumerateFiles(root, "*.jsonl", SearchOption.AllDirectories)
                        .Where(p => RelativeDepth(root, p) == depth)
                        .OrderByDescending(File.GetLastWriteTimeUtc)
                        .ToList();
                    var recentFragments = allFragments.Take(ListingPerSource).ToList();
                    var grouped = new Dictionary<string, List<string>>();
                    foreach (var path in recentFragments)
                    {
                        var threadId = CodexThreadId(path);
                        if (!grouped.TryGetValue(threadId, out var set))
                        {
                            grouped[threadId] = set = new List<string>();
                            set.AddRange(allFragments.Where(p => Path.GetFileName(p).Contains(threadId, StringComparison.OrdinalIgnoreCase)));
                        }
                        else if (!set.Contains(path))
                        {
                            set.Add(path);
                        }
                    }
                    foreach (var (threadId, paths) in grouped)
                        entries[Key(provider, threadId)] = (provider, paths.OrderBy(p => p, StringComparer.Ordinal).ToArray());
                }
                else
                {
                    foreach (var path in recent)
                        entries[Key(provider, path)] = (provider, new[] { path });
                }
            }

            _entries = entries;
            var staleCache = _cache.Keys.Where(k => !entries.ContainsKey(k)).ToList();
            foreach (var k in staleCache) _cache.Remove(k);
            var staleList = _listCache.Keys.Where(k => !entries.ContainsKey(k)).ToList();
            foreach (var k in staleList) _listCache.Remove(k);
            _lastScan = DateTime.UtcNow;
        }

        private static int RelativeDepth(string root, string path)
        {
            var rel = Path.GetRelativePath(root, path);
            return rel.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar).Length;
        }

        private static string Key(string provider, string unique) =>
            Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"{provider}:{unique}")))[..24].ToLowerInvariant();

        private static string VersionOf(List<FileInfo> stats) =>
            string.Join(";", stats.Select(s => $"{s.FullName}|{s.LastWriteTimeUtc.Ticks}|{s.Length}"));

        private static string CodexThreadId(string path)
        {
            try
            {
                using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
                for (var i = 0; i < 3; i++)
                {
                    var line = reader.ReadLine();
                    if (line == null) break;
                    using var row = JsonDocument.Parse(line);
                    if (row.RootElement.ValueKind == JsonValueKind.Object &&
                        row.RootElement.TryGetProperty("type", out var type) &&
                        type.ValueKind == JsonValueKind.String && type.GetString() == "session_meta" &&
                        row.RootElement.TryGetProperty("payload", out var payload) &&
                        payload.ValueKind == JsonValueKind.Object)
                    {
                        if (payload.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String)
                            return id.GetString()!;
                        if (payload.TryGetProperty("session_id", out var sid) && sid.ValueKind == JsonValueKind.String)
                            return sid.GetString()!;
                    }
                }
            }
            catch (Exception)
            {
                // A provider may still be writing the header; fall back to the file name.
            }
            return Path.GetFileNameWithoutExtension(path);
        }

        // ---------- parsing ----------

        private AgentSessionDetail ParseSession(string provider, string[] paths, List<FileInfo> stats)
        {
            var events = new List<AgentEvent>();
            string cwd = "", model = "", title = "";
            var sessionId = Path.GetFileNameWithoutExtension(paths[^1]);
            var total = 0;

            foreach (var path in paths)
            {
                using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
                string? line;
                while ((line = reader.ReadLine()) != null)
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    JsonDocument row;
                    try
                    {
                        row = JsonDocument.Parse(line);
                    }
                    catch (JsonException)
                    {
                        continue; // A provider may still be writing the final line.
                    }

                    using (row)
                    {
                        var root = row.RootElement;
                        if (root.ValueKind != JsonValueKind.Object) continue;

                        if (provider == "Codex" && TryGetString(root, "type", out var rowType) && rowType == "session_meta" &&
                            root.TryGetProperty("payload", out var meta) && meta.ValueKind == JsonValueKind.Object)
                        {
                            if (TryGetString(meta, "cwd", out var metaCwd)) cwd = metaCwd;
                            if (TryGetString(meta, "id", out var metaId)) sessionId = metaId;
                        }
                        if (ConfiguredName(root, out var configured)) title = configured;
                        if (provider == "Codex" && TryGetString(root, "type", out var t2) && t2 == "turn_context" &&
                            root.TryGetProperty("payload", out var tc) && TryGetString(tc, "model", out var tcModel)) model = tcModel;
                        if (TryGetString(root, "cwd", out var rowCwd)) cwd = rowCwd;
                        if (root.TryGetProperty("message", out var message) && message.ValueKind == JsonValueKind.Object &&
                            TryGetString(message, "model", out var msgModel)) model = msgModel;

                        foreach (var evt in Normalize(provider, root))
                        {
                            if (ContainsTransportEnvelope(evt.Text)) continue;
                            if (evt.Kind == "user" && string.IsNullOrEmpty(title))
                            {
                                var content = provider == "Antigravity" ? AntigravityUserText(evt.Text) : VisibleUserText(evt.Text);
                                title = FirstSentence(content);
                            }
                            if (evt.Kind == "output" && events.Count > 0 && events[^1].Kind == "tool")
                            {
                                var merged = events[^1];
                                merged.Kind = "command";
                                merged.Result = evt.Text.Length > 8000 ? evt.Text[..8000] : evt.Text;
                                merged.ResultError = ToolErrorRegex.IsMatch(evt.Text);
                            }
                            else
                            {
                                events.Add(evt);
                            }
                            total++;
                        }
                    }
                }
            }

            var newest = stats.OrderByDescending(s => s.LastWriteTimeUtc.Ticks).First();
            if (events.Count > MaxCachedEvents) events.RemoveRange(0, events.Count - MaxCachedEvents);
            if (provider == "Antigravity")
            {
                // .../brain/<id>/.system_generated/logs/transcript.jsonl
                sessionId = Path.GetFileName(Path.GetDirectoryName(Path.GetDirectoryName(Path.GetDirectoryName(paths[^1])))!);
            }
            return new AgentSessionDetail
            {
                Id = "",
                Provider = provider,
                Title = string.IsNullOrWhiteSpace(title) ? $"{provider} session" : title,
                SessionId = sessionId,
                Cwd = cwd,
                Model = model,
                Modified = newest.LastWriteTimeUtc.ToString("o"),
                Events = events,
                TotalEvents = total,
                Control = false,
            };
        }

        private static AgentSessionDetail Page(AgentSessionDetail detail, int limit, int before = 0)
        {
            limit = Math.Clamp(limit, 20, 250);
            var end = Math.Max(0, detail.Events.Count - Math.Max(0, before));
            var start = Math.Max(0, end - limit);
            var paged = new AgentSessionDetail
            {
                Id = detail.Id,
                Provider = detail.Provider,
                Title = detail.Title,
                SessionId = detail.SessionId,
                Cwd = detail.Cwd,
                Model = detail.Model,
                Modified = detail.Modified,
                TotalEvents = detail.TotalEvents,
                AvailableEvents = detail.Events.Count,
                HasMoreBefore = start > 0,
                NextBefore = Math.Max(0, before) + (end - start),
                Status = detail.Status,
                Control = detail.Control,
                Events = detail.Events[start..end].ToList(),
            };
            foreach (var evt in paged.Events)
            {
                if (evt.Text.Length > 8000) evt.Text = evt.Text[..8000] + "\n\n… Output shortened in compact view.";
                if (evt.Result is { Length: > 8000 }) evt.Result = evt.Result[..8000] + "\n\n… Output shortened in compact view.";
            }
            return paged;
        }

        private string ListingTitle(string key, string provider, string[] paths, string version)
        {
            if (_listCache.TryGetValue(key, out var cached) && cached.Version == version) return cached.Title;
            string title = "";
            try
            {
                foreach (var path in paths)
                {
                    var foundConfigured = false;
                    using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                    using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
                    var index = 0;
                    string? line;
                    while (index < 1000 && (line = reader.ReadLine()) != null)
                    {
                        index++;
                        JsonDocument row;
                        try
                        {
                            row = JsonDocument.Parse(line);
                        }
                        catch (JsonException) { continue; }
                        using (row)
                        {
                            if (row.RootElement.ValueKind != JsonValueKind.Object) continue;
                            if (ConfiguredName(row.RootElement, out var configured))
                            {
                                title = configured;
                                foundConfigured = true;
                                break;
                            }
                            if (!string.IsNullOrEmpty(title)) continue;
                            foreach (var evt in Normalize(provider, row.RootElement))
                            {
                                if (evt.Kind != "user" || ContainsTransportEnvelope(evt.Text)) continue;
                                var content = provider == "Antigravity" ? AntigravityUserText(evt.Text) : VisibleUserText(evt.Text);
                                var fallback = FirstSentence(content);
                                if (!string.IsNullOrEmpty(fallback)) title = fallback;
                                break;
                            }
                        }
                    }
                    if (foundConfigured) break;
                }
            }
            catch (IOException)
            {
                // Keep whatever title we have; discovery will retry on the next scan.
            }
            var result = string.IsNullOrWhiteSpace(title) ? $"{provider} session" : title;
            _listCache[key] = (version, result);
            return result;
        }

        // ---------- normalization (mirrors normalize() in the Python prototype) ----------

        private static IEnumerable<AgentEvent> Normalize(string provider, JsonElement row)
        {
            var stamp = TryGetString(row, "timestamp", out var ts) ? ts
                : TryGetString(row, "created_at", out var created) ? created : "";

            AgentEvent Event(string kind, string title, string text) =>
                new() { Kind = kind, Title = title, Text = text, Time = stamp };

            if (provider == "Codex")
            {
                if (!TryGetString(row, "type", out var rowType)) yield break;
                if (rowType != "response_item" && rowType != "event_msg") yield break;
                if (!row.TryGetProperty("payload", out var p) || p.ValueKind != JsonValueKind.Object) yield break;
                TryGetString(p, "type", out var kind);
                if (rowType == "response_item")
                {
                    if (kind == "message" && TryGetString(p, "role", out var role) && (role == "user" || role == "assistant"))
                    {
                        var content = TextContent(p, "content");
                        if (role == "user" && ContainsTransportEnvelope(content))
                        {
                            content = VisibleUserText(content);
                            if (ContainsTransportEnvelope(content)) yield break;
                        }
                        yield return Event(role, role, content);
                        yield break;
                    }
                    if (kind == "function_call" || kind == "custom_tool_call")
                    {
                        TryGetString(p, "name", out var name);
                        yield return Event("tool", string.IsNullOrEmpty(name) ? "Tool" : name, TextContent(p, "arguments") is { Length: > 0 } args ? args : TextContent(p, "input"));
                        yield break;
                    }
                    if (kind == "function_call_output" || kind == "custom_tool_call_output")
                    {
                        TryGetString(p, "call_id", out var callId);
                        yield return Event("output", "Tool output · " + (callId ?? ""), TextContent(p, "output"));
                        yield break;
                    }
                }
                else if (kind == "task_started" || kind == "task_complete" || kind == "turn_aborted")
                {
                    yield return Event("status", kind.Replace('_', ' '), "");
                }
                yield break;
            }

            if (provider == "Claude")
            {
                if (!TryGetString(row, "type", out var type) || (type != "user" && type != "assistant")) yield break;
                if (!row.TryGetProperty("message", out var message) || message.ValueKind != JsonValueKind.Object) yield break;
                if (!message.TryGetProperty("content", out var contentEl)) yield break;
                if (contentEl.ValueKind == JsonValueKind.String)
                {
                    yield return Event(type, type, contentEl.GetString() ?? "");
                    yield break;
                }
                if (contentEl.ValueKind != JsonValueKind.Array) yield break;
                foreach (var block in contentEl.EnumerateArray())
                {
                    if (block.ValueKind != JsonValueKind.Object) continue;
                    TryGetString(block, "type", out var blockType);
                    if (blockType == "text")
                        yield return Event(type, type, TryGetString(block, "text", out var text) ? text : "");
                    else if (blockType == "tool_use")
                    {
                        TryGetString(block, "name", out var name);
                        yield return Event("tool", string.IsNullOrEmpty(name) ? "Tool" : name,
                            block.TryGetProperty("input", out var input) ? input.ToString() : "{}");
                    }
                    else if (blockType == "tool_result")
                        yield return Event("output", "Tool output", TextContent(block, "content"));
                }
                yield break;
            }

            // Antigravity
            TryGetString(row, "type", out var agyKind);
            var agyContent = TryGetString(row, "content", out var agyC) ? agyC : "";
            if (agyKind == "USER_INPUT")
            {
                yield return Event("user", "user", AntigravityUserText(agyContent));
            }
            else if (agyKind == "PLANNER_RESPONSE" && !string.IsNullOrEmpty(agyContent))
            {
                yield return Event("assistant", "assistant", agyContent);
            }
            else if (!string.IsNullOrEmpty(agyContent))
            {
                yield return Event("output", string.IsNullOrEmpty(agyKind) ? "Activity" : agyKind, agyContent);
            }
            if (row.TryGetProperty("tool_calls", out var calls) && calls.ValueKind == JsonValueKind.Array)
            {
                foreach (var call in calls.EnumerateArray())
                {
                    if (call.ValueKind != JsonValueKind.Object) continue;
                    TryGetString(call, "name", out var callName);
                    yield return Event("tool", string.IsNullOrEmpty(callName) ? "Tool" : callName,
                        call.TryGetProperty("args", out var args) ? args.ToString() : "{}");
                }
            }
        }

        // ---------- shared text helpers ----------

        private static bool ContainsTransportEnvelope(string content) =>
            TransportEnvelopeMarkers.Any(content.Contains);

        private static string AntigravityUserText(string content)
        {
            var request = UserRequestRegex.Match(content);
            if (request.Success) return request.Groups[1].Value.Trim();
            return TagBlockRegex.Replace(content, "").Trim();
        }

        private static string VisibleUserText(string content)
        {
            var request = MyRequestRegex.Match(content);
            return (request.Success ? request.Groups[1].Value : content).Trim();
        }

        private static string FirstSentence(string content)
        {
            var compact = Regex.Replace(content, "\\s+", " ").Trim();
            if (compact.Length == 0) return "";
            var match = SentenceBoundaryRegex.Match(compact);
            var sentence = match.Success ? compact[..(match.Index + 1)] : compact;
            return sentence.Length > 140 ? sentence[..140].TrimEnd() : sentence;
        }

        private static bool ConfiguredName(JsonElement row, out string name)
        {
            name = "";
            if (row.ValueKind != JsonValueKind.Object) return false;
            // Session-level labels only; never mistake a tool name for a chat name.
            foreach (var key in TitleKeys)
            {
                if (GetPropertyIgnoreCase(row, key, out var direct) && direct.ValueKind == JsonValueKind.String)
                {
                    var value = direct.GetString();
                    if (!string.IsNullOrWhiteSpace(value)) { name = value.Trim()[..Math.Min(140, value.Trim().Length)]; return true; }
                }
            }
            if (TryGetString(row, "type", out var t) && t == "session_meta" &&
                row.TryGetProperty("payload", out var payload) && payload.ValueKind == JsonValueKind.Object)
            {
                foreach (var key in TitleKeys)
                {
                    if (GetPropertyIgnoreCase(payload, key, out var valueEl) && valueEl.ValueKind == JsonValueKind.String)
                    {
                        var value = valueEl.GetString();
                        if (!string.IsNullOrWhiteSpace(value)) { name = value.Trim()[..Math.Min(140, value.Trim().Length)]; return true; }
                    }
                }
            }
            return false;
        }

        private static string TextContent(JsonElement element, string property)
        {
            if (!element.TryGetProperty(property, out var value)) return "";
            switch (value.ValueKind)
            {
                case JsonValueKind.String:
                    return value.GetString() ?? "";
                case JsonValueKind.Array:
                    var parts = value.EnumerateArray()
                        .Where(item => item.ValueKind == JsonValueKind.Object && item.TryGetProperty("text", out var _))
                        .Select(item => item.GetProperty("text").GetString() ?? "");
                    return string.Join("\n", parts);
                default:
                    return value.ToString();
            }
        }

        private static bool TryGetString(JsonElement element, string property, out string value)
        {
            value = "";
            if (element.ValueKind != JsonValueKind.Object) return false;
            if (!GetPropertyIgnoreCase(element, property, out var el) || el.ValueKind != JsonValueKind.String) return false;
            value = el.GetString() ?? "";
            return true;
        }

        private static bool GetPropertyIgnoreCase(JsonElement element, string property, out JsonElement value)
        {
            foreach (var prop in element.EnumerateObject())
            {
                if (string.Equals(prop.Name, property, StringComparison.OrdinalIgnoreCase))
                {
                    value = prop.Value;
                    return true;
                }
            }
            value = default;
            return false;
        }
    }
}
