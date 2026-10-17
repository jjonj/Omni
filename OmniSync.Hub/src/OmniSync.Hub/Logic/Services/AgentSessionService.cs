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
using System.Threading.Tasks;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Models;
using OmniSync.Hub.Presentation.Hubs;

namespace OmniSync.Hub.Logic.Services
{
    /// <summary>
    /// Discovery, normalization and control of local Codex, Claude Code and Antigravity
    /// sessions (port of artifacts/agent-reader/agent_session_reader.py). Every session,
    /// however it was started, gets a derived attention State and Capabilities; turns
    /// started from Omni run as one headless resume process per turn and are tracked here.
    /// </summary>
    public class AgentSessionService
    {
        private const int MaxCachedEvents = 2000;
        private const int ListingPerSource = 40;
        private static readonly TimeSpan RescanInterval = TimeSpan.FromSeconds(15);
        private static readonly TimeSpan ParsedListingWindow = TimeSpan.FromHours(26);
        private static readonly TimeSpan TurnTimeout = TimeSpan.FromHours(2);
        private static readonly TimeSpan PendingCreateLifetime = TimeSpan.FromMinutes(10);
        private static readonly Regex LimitRegex = new("\\b(usage limit|rate[- ]limit(ed)?|hit your (usage )?limit|limit (has been )?reached|out of (credits|quota)|quota exceeded)\\b", RegexOptions.Compiled | RegexOptions.IgnoreCase);
        private static readonly string[] ToolTargetKeys =
        {
            "file_path", "path", "AbsolutePath", "TargetFile", "filePath", "command", "cmd", "CommandLine",
            "pattern", "Query", "Pattern", "query", "url", "description",
        };
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
        private readonly Dictionary<string, (string Version, string Title, string Cwd)> _listCache = new();
        private readonly IHubContext<RpcApiHub> _hub;

        /// <summary>A turn Omni is running for a session (one headless CLI process).</summary>
        private sealed class TurnRecord
        {
            public DateTime Started { get; init; } = DateTime.UtcNow;
            public string Message { get; init; } = "";
            public System.Diagnostics.Process? Process { get; set; }
            public bool Interrupted { get; set; }
            /// <summary>Start of the latest CLI attempt (a fallback retry restarts it).</summary>
            public DateTime LastStart { get; set; } = DateTime.UtcNow;
            public Action? Restarted { get; set; }
        }

        private sealed record FailureRecord(DateTime At, string Error, string Message);

        /// <summary>A session Omni is creating; shown as a synthetic entry until its transcript is discovered.</summary>
        private sealed class PendingCreate
        {
            public string Id { get; init; } = "";
            public string Provider { get; init; } = "";
            public string Cwd { get; init; } = "";
            public string Message { get; init; } = "";
            public TurnRecord Turn { get; init; } = new();
            public string? MatchedKey { get; set; }
            public bool Finished { get; set; }
            public DateTime? FinishedAt { get; set; }
            public string? Error { get; set; }
        }

        private readonly ConcurrentDictionary<string, TurnRecord> _turns = new();
        private readonly ConcurrentDictionary<string, FailureRecord> _failures = new();
        private readonly ConcurrentDictionary<string, PendingCreate> _creates = new();

        /// <summary>A message waiting for its session's running turn to end.</summary>
        private sealed class QueuedMessage
        {
            public string Id { get; init; } = Guid.NewGuid().ToString("N")[..10];
            public string Text { get; init; } = "";
            public DateTime At { get; init; } = DateTime.UtcNow;
            public DateTime LastAttempt { get; set; } = DateTime.MinValue;
        }

        private readonly ConcurrentDictionary<string, List<QueuedMessage>> _queues = new();

        // Queued messages are written to disk so a Hub restart does not silently drop what the phone queued.
        private static readonly string QueuesPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync", "agent_queues.json");
        private static readonly TimeSpan StaleQueuedMessageAge = TimeSpan.FromHours(24);
        private readonly object _queueFileGate = new();

        private sealed record PersistedQueuedMessage(string Id, string Text, DateTime At);

        private void SaveQueues()
        {
            try
            {
                var snapshot = new Dictionary<string, List<PersistedQueuedMessage>>();
                foreach (var (key, queue) in _queues.ToArray())
                {
                    lock (queue)
                    {
                        if (queue.Count > 0)
                            snapshot[key] = queue.Select(q => new PersistedQueuedMessage(q.Id, q.Text, q.At)).ToList();
                    }
                }
                lock (_queueFileGate)
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(QueuesPath)!);
                    var temp = QueuesPath + ".tmp";
                    File.WriteAllText(temp, JsonSerializer.Serialize(snapshot, new JsonSerializerOptions { WriteIndented = true }));
                    File.Move(temp, QueuesPath, overwrite: true);
                }
            }
            catch (Exception error)
            {
                _logger.LogWarning(error, "[AgentSessions] could not save queued messages to {Path}", QueuesPath);
            }
        }

        private void LoadQueues()
        {
            try
            {
                if (!File.Exists(QueuesPath)) return;
                var saved = JsonSerializer.Deserialize<Dictionary<string, List<PersistedQueuedMessage>>>(File.ReadAllText(QueuesPath));
                if (saved is null) return;
                var restored = 0;
                foreach (var (key, messages) in saved)
                {
                    var queue = messages
                        .Where(m => !string.IsNullOrWhiteSpace(m.Text))
                        .Select(m => new QueuedMessage { Id = m.Id, Text = m.Text, At = m.At })
                        .ToList();
                    if (queue.Count == 0) continue;
                    _queues[key] = queue;
                    restored += queue.Count;
                }
                if (restored > 0)
                    _logger.LogInformation("[AgentSessions] Restored {Count} queued message(s) from {Path}", restored, QueuesPath);
            }
            catch (Exception error)
            {
                _logger.LogWarning(error, "[AgentSessions] could not read queued messages from {Path}", QueuesPath);
            }
        }
        private readonly System.Threading.Timer _dispatchTimer;
        private readonly AgentLiveSessions _live;
        private int _dispatching;

        public AgentSessionService(ILogger<AgentSessionService> logger, IHubContext<RpcApiHub> hub)
        {
            _logger = logger;
            _hub = hub;
            _live = new AgentLiveSessions(logger);
            Task.Run(_live.EnsureClaudeHooksInstalled);
            LoadQueues();
            _dispatchTimer = new System.Threading.Timer(_ => DispatchQueues(), null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));
        }

        private static string Home => Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

        /// <summary>Providers Omni can start turns for (create, continue, hand over to).</summary>
        public static readonly string[] ControllableProviders = { "Codex", "Claude", "Antigravity" };

        // ---------- discovery ----------

        public List<AgentSessionSummary> Listing()
        {
            lock (_gate) Discover();
            Dictionary<string, (string Provider, string[] Paths)> entries;
            lock (_gate) entries = new(_entries);

            var result = new List<AgentSessionSummary>();
            var created = new Dictionary<string, DateTime>();
            foreach (var (key, entry) in entries)
            {
                try
                {
                    var stats = entry.Paths.Select(p => new FileInfo(p)).ToList();
                    var newest = stats.OrderByDescending(s => s.LastWriteTimeUtc.Ticks).First();
                    created[key] = stats.Min(s => s.CreationTimeUtc);
                    var version = VersionOf(stats);
                    var summary = new AgentSessionSummary
                    {
                        Id = key,
                        Provider = entry.Provider,
                        Modified = newest.LastWriteTimeUtc.ToString("o"),
                    };
                    var recent = DateTime.UtcNow - newest.LastWriteTimeUtc < ParsedListingWindow ||
                                 _turns.ContainsKey(key) || _failures.ContainsKey(key);
                    AgentSessionDetail? parsed = null;
                    if (recent)
                    {
                        parsed = Parsed(key, entry, stats, version);
                        summary.Title = parsed.Title;
                        summary.Cwd = parsed.Cwd;
                        summary.Model = parsed.Model;
                        summary.SessionId = parsed.SessionId;
                    }
                    else
                    {
                        (string Title, string Cwd) light;
                        lock (_gate) light = ListingInfo(key, entry.Provider, entry.Paths, version);
                        summary.Title = light.Title;
                        summary.Cwd = light.Cwd;
                        summary.SessionId = SessionIdFor(entry.Provider, entry.Paths);
                    }
                    ApplyState(summary, key, parsed?.Events, newest.LastWriteTimeUtc);
                    result.Add(summary);
                }
                catch (Exception error)
                {
                    _logger.LogWarning(error, "[AgentSessions] listing failed for {Key}", key);
                }
            }

            MatchPendingCreates(result, created);
            result.AddRange(PendingCreateSummaries());
            foreach (var summary in result) ApplyTitleOverride(summary);
            return result.OrderByDescending(s => s.Modified).ToList();
        }

        public AgentSessionDetail Read(string key, int limit = 120, int before = 0)
        {
            if (_creates.TryGetValue(key, out var pending))
                return PendingCreateDetail(pending);

            (string Provider, string[] Paths) entry;
            lock (_gate)
            {
                Discover();
                if (!_entries.TryGetValue(key, out entry!))
                    throw new FileNotFoundException($"Unknown agent session {key}");
            }

            var stats = entry.Paths.Select(p => new FileInfo(p)).ToList();
            var detail = Parsed(key, entry, stats, VersionOf(stats));
            var page = Page(detail, limit, before);
            ApplyState(page, key, detail.Events, stats.Max(s => s.LastWriteTimeUtc));
            ApplyTitleOverride(page);
            return page;
        }

        private AgentSessionDetail Parsed(string key, (string Provider, string[] Paths) entry, List<FileInfo> stats, string version)
        {
            lock (_gate)
            {
                if (_cache.TryGetValue(key, out var cached) && cached.Version == version)
                    return cached.Detail;
            }
            var detail = ParseSession(entry.Provider, entry.Paths, stats);
            detail.Id = key;
            lock (_gate) _cache[key] = (version, detail);
            return detail;
        }

        // ---------- control ----------

        /// <summary>
        /// Start one user turn on a discovered session by resuming it through the provider's
        /// headless CLI (the mechanism proven by the agent-reader roundtrips). Returns as soon
        /// as the process is launched; progress is broadcast through ReceiveAgentSendStatus
        /// and AgentSessionsChanged.
        /// </summary>
        /// <param name="mode">
        /// "auto": send now when the session is free, otherwise queue until the running turn ends.
        /// "queue": same as auto (explicit).
        /// "now": start a parallel turn even while another client is mid-turn (an Omni turn still queues,
        /// because one session can only have one headless Omni process).
        /// </param>
        public void Send(string key, string message, string mode = "auto")
        {
            var (entry, snapshot, probe) = Probe(key);
            if (mode == "auto") mode = probe.SendOptions.FirstOrDefault()?.Mode ?? "direct";
            var live = entry.Provider == "Claude" ? _live.Find(probe.SessionId) : null;
            switch (mode)
            {
                case "type" when live is { Client: "terminal" }:
                    // Typed into the open terminal session, exactly as if the user typed it there.
                    _ = Task.Run(async () =>
                    {
                        try
                        {
                            await _live.TypeIntoConsoleAsync(live.Pid, message);
                            _logger.LogInformation("[AgentSessions] Typed message into terminal pid {Pid} for {Key}", live.Pid, key);
                            Notify(key, "delivered", null);
                        }
                        catch (Exception error)
                        {
                            _logger.LogWarning(error, "[AgentSessions] Typing into terminal failed for {Key}", key);
                            Notify(key, "error", Truncate(error.Message, 300));
                        }
                    });
                    return;
                case "inbox" when live is not null && (live.Busy ?? probe.State == "working"):
                    // Hooks only fire during activity, so an idle session has nothing to run them on;
                    // only queue when there is a turn in progress to pick the message up.
                    _live.Enqueue(probe.SessionId, message);
                    _logger.LogInformation("[AgentSessions] Message for {Key} waiting in the live-session inbox ({Client}, busy=True)", key, live.Client);
                    Notify(key, "delivered-pending", null);
                    return;
                case "inbox" when live is not null:
                    // Idle live session: no hook will fire until something else happens, so run a parallel
                    // turn now instead of hanging. The reply goes into the same provider session.
                    _logger.LogInformation("[AgentSessions] Live {Client} session for {Key} is idle; starting a headless turn instead of queueing", live.Client, key);
                    break;
            }
            var busy = probe.OmniTurn || probe.State == "starting" || (probe.LiveElsewhere && mode != "now");
            if (busy || mode == "queue" && probe.State is "working" or "limited")
            {
                var queue = _queues.GetOrAdd(key, _ => new List<QueuedMessage>());
                lock (queue) queue.Add(new QueuedMessage { Text = message });
                SaveQueues();
                _logger.LogInformation("[AgentSessions] Queued message for {Key} ({Count} waiting)", key, queue.Count);
                Notify(key, "queued", null);
                return;
            }
            StartTurn(key, entry, snapshot, message);
        }

        private ((string Provider, string[] Paths) Entry, AgentSessionDetail Snapshot, AgentSessionSummary Probe) Probe(string key)
        {
            (string Provider, string[] Paths) entry;
            lock (_gate)
            {
                Discover();
                if (!_entries.TryGetValue(key, out entry!))
                    throw new FileNotFoundException($"Unknown agent session {key}");
            }
            var stats = entry.Paths.Select(p => new FileInfo(p)).ToList();
            var snapshot = Parsed(key, entry, stats, VersionOf(stats));
            if (string.IsNullOrWhiteSpace(snapshot.SessionId))
                throw new InvalidOperationException("Session has no resumable id yet.");
            var probe = new AgentSessionSummary { Id = key, Provider = entry.Provider, SessionId = snapshot.SessionId };
            ApplyState(probe, key, snapshot.Events, stats.Max(s => s.LastWriteTimeUtc));
            return (entry, snapshot, probe);
        }

        public bool CancelQueued(string key, string queuedId)
        {
            var removed = false;
            if (_queues.TryGetValue(key, out var queue))
                lock (queue) removed = queue.RemoveAll(q => q.Id == queuedId) > 0;
            if (!removed)
            {
                var (_, snapshot, _) = Probe(key);
                removed = _live.Cancel(snapshot.SessionId, queuedId);
            }
            if (removed)
            {
                SaveQueues();
                Notify(key, "changed", null);
            }
            return removed;
        }

        /// <summary>Sends queued messages once their session is free: idle, or limited with a retry every 15 minutes.</summary>
        private void DispatchQueues()
        {
            if (Interlocked.Exchange(ref _dispatching, 1) == 1) return;
            try
            {
                foreach (var (key, queue) in _queues.ToArray())
                {
                    QueuedMessage? next;
                    lock (queue) next = queue.FirstOrDefault();
                    if (next is null) continue;
                    try
                    {
                        var (entry, snapshot, probe) = Probe(key);
                        var limitedRetry = probe.State == "limited" && DateTime.UtcNow - next.LastAttempt > TimeSpan.FromMinutes(15);
                        if (probe.State != "idle" && !limitedRetry) continue;
                        lock (queue) queue.Remove(next);
                        SaveQueues();
                        next.LastAttempt = DateTime.UtcNow;
                        _logger.LogInformation("[AgentSessions] Dispatching queued message for {Key}", key);
                        StartTurn(key, entry, snapshot, next.Text);
                    }
                    catch (FileNotFoundException) when (DateTime.UtcNow - next.At > StaleQueuedMessageAge)
                    {
                        // The session's transcript is gone and the message is a day old: stop retrying it forever.
                        lock (queue) queue.Remove(next);
                        SaveQueues();
                        _logger.LogWarning("[AgentSessions] Dropped stale queued message for missing session {Key}", key);
                    }
                    catch (Exception error)
                    {
                        _logger.LogWarning(error, "[AgentSessions] Queue dispatch failed for {Key}", key);
                    }
                }
            }
            finally
            {
                Interlocked.Exchange(ref _dispatching, 0);
            }
        }

        private void StartTurn(string key, (string Provider, string[] Paths) entry, AgentSessionDetail snapshot, string message)
        {
            var (fileName, arguments) = BuildResumeCommand(entry.Provider, snapshot.SessionId);
            if (fileName is null)
                throw new NotSupportedException($"Sending to {entry.Provider} is not supported on this PC.");
            // Without --model the CLI falls back to its own default, silently switching the session to another model
            // (and paying a full prompt-cache write for the whole conversation on every cold send).
            if (entry.Provider == "Claude" && ClientModel(entry.Paths) is { } model)
                arguments = "--model " + Quote(model) + " " + arguments;

            var record = new TurnRecord { Message = message };
            if (!_turns.TryAdd(key, record))
                throw new InvalidOperationException("A turn is already running for this session.");
            _failures.TryRemove(key, out _);

            _logger.LogInformation("[AgentSessions] Send to {Provider} session {SessionId}", entry.Provider, snapshot.SessionId);
            _ = Task.Run(async () =>
            {
                try
                {
                    await RunCliAsync(entry.Provider, fileName, arguments, snapshot.Cwd, message, record);
                    Notify(key, record.Interrupted ? "interrupted" : "completed", null);
                }
                catch (Exception error)
                {
                    if (!record.Interrupted)
                    {
                        _failures[key] = new FailureRecord(DateTime.UtcNow, Truncate(error.Message, 300), message);
                        _logger.LogWarning(error, "[AgentSessions] Turn failed for {Key}", key);
                    }
                    Notify(key, record.Interrupted ? "interrupted" : "error", record.Interrupted ? null : Truncate(error.Message, 300));
                }
                finally
                {
                    _turns.TryRemove(key, out _);
                    Notify(key, "changed", null);
                }
            });
            Notify(key, "sending", null);
        }

        /// <summary>Re-send the message of the last failed turn (session or pending creation).</summary>
        public void Retry(string id)
        {
            if (_creates.TryRemove(id, out var pending))
            {
                Create(pending.Provider, pending.Cwd, pending.Message);
                return;
            }
            if (!_failures.TryGetValue(id, out var failure))
                throw new InvalidOperationException("There is no failed turn to retry.");
            Send(id, failure.Message);
        }

        /// <summary>Stop a turn Omni started (kills its CLI process tree). Turns run by other clients cannot be stopped.</summary>
        public bool Interrupt(string id)
        {
            var record = _turns.TryGetValue(id, out var turn) ? turn
                : _creates.TryGetValue(id, out var pending) && !pending.Finished ? pending.Turn : null;
            if (record?.Process is null) return false;
            record.Interrupted = true;
            try { record.Process.Kill(entireProcessTree: true); }
            catch (Exception error) { _logger.LogWarning(error, "[AgentSessions] Interrupt failed for {Id}", id); }
            _logger.LogInformation("[AgentSessions] Interrupted turn for {Id}", id);
            return true;
        }

        // ---------- rename / delete ----------

        private static readonly string TitleOverridesPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync", "agent_session_titles.json");
        private Dictionary<string, string>? _titleOverrides;

        /// <summary>Omni-side titles keyed by session key; provider transcripts are never rewritten.</summary>
        private Dictionary<string, string> TitleOverrides()
        {
            if (_titleOverrides is not null) return _titleOverrides;
            try
            {
                _titleOverrides = File.Exists(TitleOverridesPath)
                    ? JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(TitleOverridesPath)) ?? new()
                    : new();
            }
            catch (Exception error)
            {
                _logger.LogWarning(error, "[AgentSessions] could not read {Path}", TitleOverridesPath);
                _titleOverrides = new();
            }
            return _titleOverrides;
        }

        private void SaveTitleOverrides()
        {
            Directory.CreateDirectory(Path.GetDirectoryName(TitleOverridesPath)!);
            File.WriteAllText(TitleOverridesPath, JsonSerializer.Serialize(TitleOverrides(), new JsonSerializerOptions { WriteIndented = true }));
        }

        private void ApplyTitleOverride(AgentSessionStateBase target)
        {
            lock (_gate)
            {
                if (TitleOverrides().TryGetValue(target.Id, out var title)) target.Title = title;
            }
        }

        /// <summary>Rename a session in Omni. A blank title restores the provider's own title.</summary>
        public void Rename(string key, string title)
        {
            if (key.StartsWith("new-", StringComparison.Ordinal))
                throw new InvalidOperationException("Wait for the session to finish starting before renaming it.");
            title = title.Trim();
            lock (_gate)
            {
                if (title.Length == 0) TitleOverrides().Remove(key);
                else TitleOverrides()[key] = Truncate(title, 200);
                SaveTitleOverrides();
            }
            _logger.LogInformation("[AgentSessions] Renamed {Key} to \"{Title}\"", key, title);
            Notify(key, "changed", null);
        }

        /// <summary>
        /// Permanently delete a session's transcript files from the PC (Codex rollout fragments,
        /// a Claude .jsonl plus its sidecar folder, or an Antigravity brain folder).
        /// </summary>
        public void Delete(string key)
        {
            if (_creates.TryGetValue(key, out var pending))
            {
                if (!pending.Finished)
                    throw new InvalidOperationException("Stop the session before deleting it.");
                _creates.TryRemove(key, out _);
                Notify(key, "deleted", null);
                return;
            }
            if (_turns.ContainsKey(key))
                throw new InvalidOperationException("Stop the running turn before deleting this session.");

            (string Provider, string[] Paths) entry;
            lock (_gate)
            {
                Discover();
                if (!_entries.TryGetValue(key, out entry!))
                    throw new FileNotFoundException("That session no longer exists on the PC.");
            }

            var (files, folders) = DeletionTargets(entry.Provider, entry.Paths);
            foreach (var file in files.Where(File.Exists)) File.Delete(file);
            foreach (var folder in folders.Where(Directory.Exists)) Directory.Delete(folder, recursive: true);

            _queues.TryRemove(key, out _);
            SaveQueues();
            _failures.TryRemove(key, out _);
            lock (_gate)
            {
                _entries.Remove(key);
                _cache.Remove(key);
                _listCache.Remove(key);
                if (TitleOverrides().Remove(key)) SaveTitleOverrides();
            }
            _logger.LogInformation("[AgentSessions] Deleted {Provider} session {Key}: {Files} files, {Folders} folders",
                entry.Provider, key, files.Count, folders.Count);
            Notify(key, "deleted", null);
        }

        private static (List<string> Files, List<string> Folders) DeletionTargets(string provider, string[] paths)
        {
            var files = new List<string>();
            var folders = new List<string>();
            foreach (var path in paths)
            {
                switch (provider)
                {
                    case "Antigravity":
                        // .../brain/<id>/.system_generated/logs/transcript.jsonl -> .../brain/<id>
                        var brainEntry = Path.GetDirectoryName(Path.GetDirectoryName(Path.GetDirectoryName(path)));
                        if (brainEntry is not null &&
                            string.Equals(Path.GetFileName(Path.GetDirectoryName(brainEntry)), "brain", StringComparison.OrdinalIgnoreCase))
                            folders.Add(brainEntry);
                        else
                            files.Add(path);
                        break;
                    case "Claude":
                        files.Add(path);
                        // <session>.jsonl keeps subagent transcripts and tool results in a sibling <session>/ folder.
                        folders.Add(Path.Combine(Path.GetDirectoryName(path)!, Path.GetFileNameWithoutExtension(path)));
                        break;
                    default:
                        files.Add(path);
                        break;
                }
            }
            return (files, folders);
        }

        /// <summary>Start a new provider session in <paramref name="cwd"/> with a first message. Returns the pending id.</summary>
        public string Create(string provider, string cwd, string message)
        {
            if (!ControllableProviders.Contains(provider))
                throw new NotSupportedException($"Creating {provider} sessions is not supported.");
            if (string.IsNullOrWhiteSpace(cwd) || !Directory.Exists(cwd))
                throw new DirectoryNotFoundException($"Folder does not exist on the PC: {cwd}");
            var (fileName, arguments) = BuildCreateCommand(provider);
            if (fileName is null)
                throw new NotSupportedException($"{provider} CLI was not found on this PC.");

            var pending = new PendingCreate
            {
                Id = "new-" + Guid.NewGuid().ToString("N")[..12],
                Provider = provider,
                Cwd = cwd.TrimEnd('\\', '/'),
                Message = message,
            };
            _creates[pending.Id] = pending;
            // A fallback retry starts a second session; forget a match made against the failed first attempt.
            pending.Turn.Restarted = () =>
            {
                if (pending.MatchedKey is { } matched) _turns.TryRemove(matched, out _);
                pending.MatchedKey = null;
            };
            _logger.LogInformation("[AgentSessions] Creating {Provider} session in {Cwd}", provider, pending.Cwd);
            _ = Task.Run(async () =>
            {
                try
                {
                    await RunCliAsync(provider, fileName, arguments, pending.Cwd, message, pending.Turn);
                    if (pending.Turn.Interrupted) pending.Error = "Stopped before the session finished starting.";
                }
                catch (Exception error)
                {
                    pending.Error = pending.Turn.Interrupted ? "Stopped before the session finished starting." : Truncate(error.Message, 300);
                    _logger.LogWarning(error, "[AgentSessions] Create failed for {Provider} in {Cwd}", provider, pending.Cwd);
                }
                finally
                {
                    pending.Finished = true;
                    pending.FinishedAt = DateTime.UtcNow;
                    if (pending.MatchedKey is { } key) _turns.TryRemove(key, out _);
                    if (pending.MatchedKey is not null && pending.Error is null) _creates.TryRemove(pending.Id, out _);
                    Notify(pending.MatchedKey ?? pending.Id, pending.Error is null ? "completed" : "error", pending.Error);
                }
            });
            Notify(pending.Id, "starting", null);
            return pending.Id;
        }

        private async Task RunCliAsync(string provider, string fileName, string arguments, string cwd, string message, TurnRecord record)
        {
            try
            {
                await RunCliOnceAsync(provider, fileName, arguments, cwd, message, record);
            }
            catch (InvalidOperationException error) when (provider == "Codex" && !record.Interrupted &&
                                                          error.Message.Contains("model is not supported", StringComparison.OrdinalIgnoreCase))
            {
                // The model in ~/.codex/config.toml may be one the CLI's sign-in cannot use headlessly;
                // fall back to the CLI's default model (and default config) rather than failing the turn.
                _logger.LogWarning("[AgentSessions] Codex rejected the configured model; retrying with --ignore-user-config");
                var fallback = Regex.Replace(arguments, "(^|\\s)exec\\s", m => m.Value + "--ignore-user-config ", RegexOptions.None, TimeSpan.FromSeconds(1));
                record.Restarted?.Invoke();
                await RunCliOnceAsync(provider, fileName, fallback, cwd, message, record);
            }
        }

        private async Task RunCliOnceAsync(string provider, string fileName, string arguments, string cwd, string message, TurnRecord record)
        {
            var scratch = Path.Combine(Path.GetTempPath(), "omni-agent-send");
            Directory.CreateDirectory(scratch);
            var psi = new System.Diagnostics.ProcessStartInfo
            {
                FileName = fileName,
                Arguments = arguments,
                WorkingDirectory = string.IsNullOrWhiteSpace(cwd) || !Directory.Exists(cwd) ? scratch : cwd,
                UseShellExecute = false,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            };
            if (provider == "Antigravity")
            {
                // agy takes the prompt as an argument; the others read stdin.
                psi.Arguments = arguments + " " + Quote(message);
            }

            record.LastStart = DateTime.UtcNow;
            using var process = System.Diagnostics.Process.Start(psi)
                ?? throw new InvalidOperationException($"Failed to launch {fileName}");
            record.Process = process;
            if (provider != "Antigravity")
                await process.StandardInput.WriteAsync(message);
            process.StandardInput.Close();

            var stdoutTask = process.StandardOutput.ReadToEndAsync();
            var stderrTask = process.StandardError.ReadToEndAsync();
            var exitTask = process.WaitForExitAsync();
            var completed = await Task.WhenAny(exitTask, Task.Delay(TurnTimeout));
            if (completed != exitTask)
            {
                // Timeout: kill the process tree so no orphan CLI remains.
                try { process.Kill(entireProcessTree: true); } catch (Exception) { }
                throw new TimeoutException($"{provider} turn timed out after {TurnTimeout.TotalMinutes:0} minutes");
            }
            var stdout = await stdoutTask;
            var stderr = await stderrTask;
            if (record.Interrupted) return;
            if (process.ExitCode != 0)
                throw new InvalidOperationException($"{provider}: {CliError(stdout, stderr, process.ExitCode)}");
            _logger.LogInformation("[AgentSessions] {Provider} turn completed ({Bytes} bytes stdout)", provider, stdout.Length);
        }

        /// <summary>result / message / error.message of a JSON error line; a message that is itself JSON is unwrapped.</summary>
        private static string? JsonErrorMessage(JsonElement root, int depth = 0)
        {
            string? text = null;
            if (TryGetString(root, "result", out var result) && result.Length > 0) text = result;
            else if (TryGetString(root, "message", out var message) && message.Length > 0) text = message;
            else if (root.TryGetProperty("error", out var err))
                text = err.ValueKind == JsonValueKind.Object ? JsonErrorMessage(err, depth + 1) ?? err.ToString() : err.ToString();
            if (text is not null && depth < 3 && text.TrimStart().StartsWith('{'))
            {
                try
                {
                    using var nested = JsonDocument.Parse(text);
                    return JsonErrorMessage(nested.RootElement, depth + 1) ?? text;
                }
                catch (JsonException) { }
            }
            return text;
        }

        /// <summary>The provider's own error message from --json/--output-format json output, else the first stderr line.</summary>
        private static string CliError(string stdout, string stderr, int exitCode)
        {
            foreach (var line in stdout.Split('\n').Reverse())
            {
                var trimmed = line.Trim();
                if (!trimmed.StartsWith('{')) continue;
                try
                {
                    using var doc = JsonDocument.Parse(trimmed);
                    var root = doc.RootElement;
                    var found = JsonErrorMessage(root);
                    if (found is not null) return Truncate(found, 300);
                }
                catch (JsonException) { }
            }
            var firstErr = stderr.Split('\n').Select(l => l.Trim()).FirstOrDefault(l => l.Length > 0);
            return Truncate(firstErr ?? $"exited with code {exitCode}", 300);
        }

        private void Notify(string id, string state, string? error)
        {
            lock (_gate) _lastScan = DateTime.MinValue; // pick up transcript changes on the next listing
            try
            {
                if (state != "changed")
                    _ = _hub.Clients.All.SendAsync("ReceiveAgentSendStatus", JsonSerializer.Serialize(new { Id = id, State = state, Error = error }));
                _ = _hub.Clients.All.SendAsync("AgentSessionsChanged", id);
            }
            catch (Exception broadcastError)
            {
                _logger.LogWarning(broadcastError, "[AgentSessions] broadcast failed for {Id}", id);
            }
        }

        // ---------- attention state ----------

        private void ApplyState(AgentSessionStateBase target, string key, List<AgentEvent>? events, DateTime modifiedUtc)
        {
            var now = DateTime.UtcNow;
            var live = target.Provider == "Claude" ? _live.Find(target.SessionId) : null;
            target.State = "idle";
            target.StateSince = modifiedUtc.ToString("o");
            target.LiveElsewhere = false;
            target.OmniTurn = false;
            target.Error = null;
            target.Question = null;
            var last = events is { Count: > 0 } ? events[^1] : null;
            target.Activity = LastAssistantLine(events);

            if (_turns.TryGetValue(key, out var turn))
            {
                target.State = "working";
                target.OmniTurn = true;
                target.StateSince = turn.Started.ToString("o");
                target.Activity = modifiedUtc > turn.Started && last is { Kind: "tool" or "command" }
                    ? ToolLabel(last) : "Running turn from Omni";
            }
            else if (_failures.TryGetValue(key, out var failure))
            {
                if (modifiedUtc > failure.At.AddSeconds(5))
                {
                    _failures.TryRemove(key, out _); // the transcript moved on since the failure
                    ApplyState(target, key, events, modifiedUtc);
                    return;
                }
                target.State = "error";
                target.StateSince = failure.At.ToString("o");
                target.Error = failure.Error;
                target.Activity = failure.Error;
            }
            else if (last is { Kind: "tool", Title: "AskUserQuestion" } && last.Result is null && ParseQuestion(last.Text) is { } question)
            {
                target.State = "question";
                target.Question = question;
                target.StateSince = EventTimeOr(last, modifiedUtc);
                target.Activity = question.Text;
            }
            else if (last is { Kind: "assistant" or "output" or "command" } && last.Text.Length < 1200 && LimitRegex.IsMatch(last.Text + " " + last.Result))
            {
                target.State = "limited";
                target.StateSince = EventTimeOr(last, modifiedUtc);
                target.Activity = FirstSentence(last.Text);
            }
            // A Claude client with OmniAgentHook reports busy/idle exactly; otherwise infer it from the transcript.
            // The busy flag is ignored once the transcript has been quiet for a while (an Esc-interrupted turn fires no Stop hook).
            else if (live?.Busy is bool reportedBusy
                         ? reportedBusy && now - modifiedUtc < TimeSpan.FromMinutes(15)
                         : IsAdvancing(target.Provider, events, modifiedUtc, now))
            {
                target.State = "working";
                target.LiveElsewhere = true;
                var lastUser = events?.LastOrDefault(e => e.Kind == "user");
                target.StateSince = lastUser is null ? modifiedUtc.ToString("o") : EventTimeOr(lastUser, modifiedUtc);
                target.Activity = last is { Kind: "tool" or "command" } ? ToolLabel(last) : "Working";
            }

            target.OpenIn = live?.Client switch { "app" => "Claude app", "terminal" => "terminal", _ => "" };
            target.Queued = _queues.TryGetValue(key, out var queue)
                ? QueueSnapshot(queue)
                : new List<AgentQueuedMessage>();
            if (live is not null)
                target.Queued.AddRange(_live.Pending(target.SessionId).Select(m => new AgentQueuedMessage
                    { Id = m.Id, Text = m.Text, At = m.At, Delivery = "pc-pickup" }));
            target.TerminalCommand = TerminalCommand(target.Provider, target.SessionId);
            target.SendOptions = SendOptionsFor(target, live);
            target.Capabilities = CapabilitiesFor(target);
        }

        /// <summary>How a message can reach this session right now; the first option is the default.</summary>
        private List<AgentSendOption> SendOptionsFor(AgentSessionStateBase s, AgentLiveClient? live)
        {
            var options = new List<AgentSendOption>();
            var liveBusy = live?.Busy ?? s.State == "working";
            if (s.OmniTurn || s.State == "starting")
            {
                options.Add(new("queue", "Queue for after this turn", "Sends automatically when the running turn ends"));
            }
            else if (live is { Client: "terminal" })
            {
                options.Add(new("type", "Type into the PC terminal",
                    liveBusy ? "Typed into the open terminal; the agent picks it up after its current step" : "Typed into the open terminal session as if you typed it there"));
                options.Add(new("now", "Run separately now", "Starts a separate turn outside the open terminal's conversation"));
            }
            else if (live is { Client: "app" })
            {
                if (liveBusy)
                {
                    options.Add(new("inbox", "Deliver into the running turn", "The agent in the Claude app sees it after its next step"));
                    options.Add(new("now", "Run separately now", "Answers right away, but outside the Claude app window's conversation"));
                }
                else
                {
                    // No hook fires on an idle session, so running now is the only choice that gets a reply.
                    options.Add(new("now", "Run now in the same session", "Reply arrives right away; it appears in the Claude app window on its next activity"));
                }
            }
            else if (s.LiveElsewhere)
            {
                options.Add(new("queue", "Queue for after this turn", "Sends automatically when the other client's turn ends"));
                options.Add(new("now", "Send now", "Starts a parallel turn on this session right away"));
            }
            return options;
        }

        private static List<AgentQueuedMessage> QueueSnapshot(List<QueuedMessage> queue)
        {
            lock (queue) return queue.Select(q => new AgentQueuedMessage { Id = q.Id, Text = q.Text, At = q.At.ToString("o") }).ToList();
        }

        private static bool IsAdvancing(string provider, List<AgentEvent>? events, DateTime modifiedUtc, DateTime now)
        {
            if (events is not { Count: > 0 }) return false;
            var age = now - modifiedUtc;
            if (provider == "Codex")
            {
                var status = events.LastOrDefault(e => e.Kind == "status");
                if (status is not null && status.Title == "task started" && age < TimeSpan.FromMinutes(15)) return true;
                if (status is not null && status.Title is "task complete" or "turn aborted") return false;
            }
            // A turn ends with the agent's final message; anything after it means work is in progress.
            return events[^1].Kind != "assistant" && age < TimeSpan.FromMinutes(3);
        }

        private AgentCapabilities CapabilitiesFor(AgentSessionStateBase s)
        {
            var resumable = !string.IsNullOrWhiteSpace(s.SessionId) && BuildResumeCommand(s.Provider, s.SessionId).FileName is not null;
            var canSend = (resumable || s.SendOptions.Count > 0) && s.State != "starting";
            var note = TurnModeNote(s.Provider);
            if (!resumable) note = $"{s.Provider} CLI was not found on the Hub PC, so Omni can only read this session.";
            else if (!string.IsNullOrEmpty(s.OpenIn)) note += $" Open in the {s.OpenIn} on the PC: messages are delivered into that conversation.";
            else if (s.LiveElsewhere) note += " Another client is mid-turn: messages queue until it ends, or can start a parallel turn now.";
            else if (s.OmniTurn) note += " Messages sent now queue until the current turn ends.";
            return new AgentCapabilities
            {
                CanSend = canSend,
                CanQueue = resumable,
                CanSendParallel = s.SendOptions.Any(o => o.Mode == "now"),
                CanInterrupt = s.OmniTurn,
                CanAnswerQuestions = canSend && s.State == "question",
                CanApproveTools = false,
                CanChangeModel = false,
                CanObserveLiveEvents = false,
                CanOpenTerminal = !string.IsNullOrEmpty(s.TerminalCommand),
                Backend = "Headless CLI resume, one process per turn; transcript polling",
                Note = note,
            };
        }

        private static string TurnModeNote(string provider) =>
            ControllableProviders.Contains(provider)
                ? "Turns from Omni run with full permissions; every tool approval is automatic."
                : "";

        private static string TerminalCommand(string provider, string sessionId)
        {
            if (string.IsNullOrWhiteSpace(sessionId)) return "";
            return provider switch
            {
                "Codex" => $"codex resume {sessionId}",
                "Claude" => $"claude --resume {sessionId}",
                "Antigravity" => $"agy --conversation {sessionId}",
                _ => "",
            };
        }

        private static AgentQuestion? ParseQuestion(string input)
        {
            try
            {
                using var doc = JsonDocument.Parse(input);
                if (!doc.RootElement.TryGetProperty("questions", out var questions) || questions.ValueKind != JsonValueKind.Array) return null;
                foreach (var q in questions.EnumerateArray())
                {
                    if (!TryGetString(q, "question", out var text)) continue;
                    var options = new List<string>();
                    if (q.TryGetProperty("options", out var opts) && opts.ValueKind == JsonValueKind.Array)
                        foreach (var o in opts.EnumerateArray())
                            if (TryGetString(o, "label", out var label)) options.Add(label);
                            else if (o.ValueKind == JsonValueKind.String) options.Add(o.GetString() ?? "");
                    return new AgentQuestion { Text = text, Options = options.Where(o => o.Length > 0).ToList() };
                }
            }
            catch (JsonException) { }
            return null;
        }

        private static string ToolLabel(AgentEvent evt)
        {
            var target = "";
            try
            {
                using var doc = JsonDocument.Parse(evt.Text);
                if (doc.RootElement.ValueKind == JsonValueKind.Object)
                {
                    foreach (var key in ToolTargetKeys)
                    {
                        if (!GetPropertyIgnoreCase(doc.RootElement, key, out var value)) continue;
                        target = value.ValueKind == JsonValueKind.Array
                            ? string.Join(" ", value.EnumerateArray().Select(v => v.ToString()))
                            : value.ToString();
                        if (target.Length > 0) break;
                    }
                }
            }
            catch (JsonException)
            {
                target = evt.Text.Split('\n').FirstOrDefault(l => l.Trim().Length > 0)?.Trim() ?? "";
            }
            if (target.Contains('\\') || target.Contains('/'))
            {
                var file = Path.GetFileName(target.Trim().TrimEnd('\\', '/'));
                if (file.Length > 0 && !target.Contains(' ')) target = file;
            }
            target = Regex.Replace(target, "\\s+", " ").Trim();
            if (target.Length > 80) target = target[..80] + "…";
            return target.Length == 0 ? evt.Title : $"{evt.Title} · {target}";
        }

        private static string LastAssistantLine(List<AgentEvent>? events)
        {
            var text = events?.LastOrDefault(e => e.Kind == "assistant")?.Text ?? "";
            var line = text.Split('\n').Select(l => l.Trim().TrimStart('#', '*', '-', ' ')).FirstOrDefault(l => l.Length > 0) ?? "";
            // One-line preview: keep link labels, drop Markdown emphasis.
            line = Regex.Replace(line, "\\[([^\\]]+)\\]\\([^)]*\\)", "$1").Replace("**", "").Replace("`", "");
            return line.Length > 140 ? line[..140].TrimEnd() + "…" : line;
        }

        private static string EventTimeOr(AgentEvent evt, DateTime fallbackUtc) =>
            DateTime.TryParse(evt.Time, null, System.Globalization.DateTimeStyles.AdjustToUniversal | System.Globalization.DateTimeStyles.AssumeUniversal, out var t)
                ? t.ToString("o") : fallbackUtc.ToString("o");

        // ---------- pending creations ----------

        private void MatchPendingCreates(List<AgentSessionSummary> sessions, Dictionary<string, DateTime> created)
        {
            foreach (var pending in _creates.Values.Where(p => p.MatchedKey is null))
            {
                var match = sessions
                    .Where(s => s.Provider == pending.Provider &&
                                string.Equals(s.Cwd.TrimEnd('\\', '/'), pending.Cwd, StringComparison.OrdinalIgnoreCase) &&
                                created.TryGetValue(s.Id, out var c) && c >= pending.Turn.LastStart.AddSeconds(-10))
                    .OrderBy(s => created[s.Id])
                    .FirstOrDefault();
                if (match is null) continue;
                pending.MatchedKey = match.Id;
                _logger.LogInformation("[AgentSessions] Pending {Pending} matched new session {Key}", pending.Id, match.Id);
                if (!pending.Finished) _turns.TryAdd(match.Id, pending.Turn);
                else _creates.TryRemove(pending.Id, out _);
                // A failed first turn can still leave a transcript: the failure belongs to that session now.
                if (pending.Error is not null)
                    _failures[match.Id] = new FailureRecord(pending.FinishedAt ?? DateTime.UtcNow, pending.Error, pending.Message);
                ApplyState(match, match.Id, null, DateTime.TryParse(match.Modified, out var m) ? m.ToUniversalTime() : DateTime.UtcNow);
            }
        }

        private IEnumerable<AgentSessionSummary> PendingCreateSummaries()
        {
            foreach (var pending in _creates.Values.ToList())
            {
                if (pending.MatchedKey is not null) continue;
                if (pending.FinishedAt is { } done && DateTime.UtcNow - done > PendingCreateLifetime)
                {
                    _creates.TryRemove(pending.Id, out _);
                    continue;
                }
                var summary = new AgentSessionSummary();
                FillPending(summary, pending);
                yield return summary;
            }
        }

        private AgentSessionDetail PendingCreateDetail(PendingCreate pending)
        {
            var detail = new AgentSessionDetail
            {
                Events = new List<AgentEvent> { new() { Kind = "user", Title = "user", Text = pending.Message, Time = pending.Turn.Started.ToString("o") } },
                TotalEvents = 1,
                AvailableEvents = 1,
            };
            FillPending(detail, pending);
            return detail;
        }

        private static void FillPending(AgentSessionStateBase target, PendingCreate pending)
        {
            var failed = pending.Error is not null;
            target.Id = pending.Id;
            target.Provider = pending.Provider;
            target.Title = FirstSentence(pending.Message) is { Length: > 0 } t ? t : $"New {pending.Provider} session";
            target.Cwd = pending.Cwd;
            target.Modified = (pending.FinishedAt ?? pending.Turn.Started).ToString("o");
            target.State = failed ? "error" : "starting";
            target.StateSince = (pending.FinishedAt ?? pending.Turn.Started).ToString("o");
            target.Activity = failed ? pending.Error! : "Starting new session…";
            target.Error = pending.Error;
            target.OmniTurn = !failed && !pending.Finished;
            target.Capabilities = new AgentCapabilities
            {
                CanInterrupt = target.OmniTurn,
                Backend = "Headless CLI, new session",
                Note = failed ? "Retry starts the session again with the same first message." : TurnModeNote(pending.Provider),
            };
        }

        private static string Quote(string value) => "\"" + value.Replace("\"", "\\\"") + "\"";

        private static string Truncate(string value, int max) =>
            string.IsNullOrEmpty(value) || value.Length <= max ? value : value[..max];

        /// <summary>Same safe-mode invocation as <see cref="BuildResumeCommand"/>, without a session to resume.</summary>
        private static (string? FileName, string Arguments) BuildCreateCommand(string provider)
        {
            var (fileName, arguments) = BuildResumeCommand(provider, "__SESSION__");
            if (fileName is null) return (null, "");
            var resumeTail = provider switch
            {
                "Codex" => " resume \"__SESSION__\" -",
                "Claude" => " --resume \"__SESSION__\"",
                _ => " --conversation \"__SESSION__\"",
            };
            var newTail = provider == "Codex" ? " -" : "";
            return (fileName, arguments.Replace(resumeTail, newTail));
        }

        /// <summary>The model the user's own client last ran this Claude session with (Omni's headless sdk-cli turns excluded).</summary>
        private static string? ClientModel(string[] paths)
        {
            string? model = null;
            foreach (var path in paths)
            {
                try
                {
                    foreach (var line in File.ReadLines(path))
                    {
                        if (!line.Contains("\"model\"") || line.Contains("\"entrypoint\":\"sdk-cli\"")) continue;
                        try
                        {
                            using var doc = JsonDocument.Parse(line);
                            if (doc.RootElement.TryGetProperty("message", out var message) && message.ValueKind == JsonValueKind.Object &&
                                TryGetString(message, "model", out var found) && !found.StartsWith('<'))
                                model = found;
                        }
                        catch (JsonException) { }
                    }
                }
                catch (IOException) { }
            }
            return model;
        }

        private static (string? FileName, string Arguments) BuildResumeCommand(string provider, string sessionId)
        {
            var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var userProfile = Home;
            switch (provider)
            {
                case "Codex":
                {
                    // Full autonomy: no sandbox, no approval prompts, the user's own config/MCP servers.
                    const string execArgs = "exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check --json resume ";
                    var launcher = Path.Combine(appData, "npm", "node_modules", "@openai", "codex", "bin", "codex.js");
                    if (File.Exists(launcher))
                        return ("node", Quote(launcher) + " " + execArgs + Quote(sessionId) + " -");
                    // Standalone installer layout.
                    var executable = Path.Combine(localAppData, "Programs", "OpenAI", "Codex", "bin", "codex.exe");
                    if (!File.Exists(executable)) return (null, "");
                    return (executable, execArgs + Quote(sessionId) + " -");
                }
                case "Claude":
                {
                    var executable = Path.Combine(userProfile, ".local", "bin", "claude.exe");
                    if (!File.Exists(executable)) return (null, "");
                    return (executable,
                        "--print --output-format json --dangerously-skip-permissions --resume " + Quote(sessionId));
                }
                case "Antigravity":
                {
                    var executable = Path.Combine(localAppData, "agy", "bin", "agy.exe");
                    if (!File.Exists(executable)) return (null, "");
                    return (executable,
                        "--print --output-format json --dangerously-skip-permissions --print-timeout 0 --conversation " + Quote(sessionId));
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

        private (string Title, string Cwd) ListingInfo(string key, string provider, string[] paths, string version)
        {
            if (_listCache.TryGetValue(key, out var cached) && cached.Version == version) return (cached.Title, cached.Cwd);
            string title = "", cwd = "";
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
                            if (cwd.Length == 0)
                            {
                                if (TryGetString(row.RootElement, "cwd", out var rowCwd)) cwd = rowCwd;
                                else if (row.RootElement.TryGetProperty("payload", out var payload) && TryGetString(payload, "cwd", out var metaCwd)) cwd = metaCwd;
                            }
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
            _listCache[key] = (version, result, cwd);
            return (result, cwd);
        }

        private static string SessionIdFor(string provider, string[] paths) => provider switch
        {
            "Codex" => CodexThreadId(paths[^1]),
            // .../brain/<id>/.system_generated/logs/transcript.jsonl
            "Antigravity" => Path.GetFileName(Path.GetDirectoryName(Path.GetDirectoryName(Path.GetDirectoryName(paths[^1])))!),
            _ => Path.GetFileNameWithoutExtension(paths[^1]),
        };

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
                if (!TryGetString(row, "type", out var type)) yield break;
                // Phone messages delivered by OmniAgentHook into an open client are stored as hook context
                // (mid-turn / next prompt) or Stop-hook feedback (turn end); show them as the user's messages.
                if (type == "attachment" && row.TryGetProperty("attachment", out var attachment) &&
                    TryGetString(attachment, "type", out var attachmentType) && attachmentType == "hook_additional_context" &&
                    attachment.TryGetProperty("content", out var hookContent) && hookContent.ValueKind == JsonValueKind.Array)
                {
                    foreach (var part in hookContent.EnumerateArray())
                        if (part.ValueKind == JsonValueKind.String && PhoneMessage(part.GetString() ?? "") is { } phone)
                            yield return Event("user", "phone", phone);
                    yield break;
                }
                if (type != "user" && type != "assistant") yield break;
                if (!row.TryGetProperty("message", out var message) || message.ValueKind != JsonValueKind.Object) yield break;
                if (!message.TryGetProperty("content", out var contentEl)) yield break;
                if (contentEl.ValueKind == JsonValueKind.String)
                {
                    var content = contentEl.GetString() ?? "";
                    yield return PhoneMessage(content) is { } phone ? Event("user", "phone", phone) : Event(type, type, content);
                    yield break;
                }
                if (contentEl.ValueKind != JsonValueKind.Array) yield break;
                foreach (var block in contentEl.EnumerateArray())
                {
                    if (block.ValueKind != JsonValueKind.Object) continue;
                    TryGetString(block, "type", out var blockType);
                    if (blockType == "text")
                    {
                        var text = TryGetString(block, "text", out var t) ? t : "";
                        yield return type == "user" && PhoneMessage(text) is { } phone ? Event("user", "phone", phone) : Event(type, type, text);
                    }
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

        private const string PhoneMarker = "sent from the Omni phone app";

        /// <summary>The user's text from an OmniAgentHook delivery (context or "Stop hook feedback: ..."), else null.</summary>
        private static string? PhoneMessage(string content)
        {
            if (!content.Contains(PhoneMarker, StringComparison.Ordinal)) return null;
            var start = content.IndexOf("\n\n", content.IndexOf(PhoneMarker, StringComparison.Ordinal), StringComparison.Ordinal);
            if (start < 0) return null;
            var text = content[(start + 2)..];
            var instruction = text.LastIndexOf("\n\n(The user cannot see this message", StringComparison.Ordinal);
            return (instruction >= 0 ? text[..instruction] : text).Trim();
        }

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
