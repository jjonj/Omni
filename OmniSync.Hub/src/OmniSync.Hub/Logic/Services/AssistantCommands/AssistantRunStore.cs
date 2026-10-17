using System.Text.Json;
using System.IO;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class AssistantRunStore
{
    private readonly ILogger<AssistantRunStore> _logger;
    private readonly string _storePath;
    private readonly object _sync = new();
    private readonly Dictionary<Guid, CommandRun> _runs;

    public AssistantRunStore(
        ILogger<AssistantRunStore> logger,
        IConfiguration configuration)
    {
        _logger = logger;
        var configuredPath = configuration["Assistant:RunStorePath"];
        if (!string.IsNullOrWhiteSpace(configuredPath))
        {
            _storePath = configuredPath;
        }
        else
        {
            var directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "OmniSync");
            Directory.CreateDirectory(directory);
            _storePath = Path.Combine(directory, "assistant_runs.json");
        }

        var parent = Path.GetDirectoryName(_storePath);
        if (!string.IsNullOrWhiteSpace(parent))
            Directory.CreateDirectory(parent);

        _runs = Load();
    }

    public CommandRun? FindExisting(AssistantRequest request)
    {
        lock (_sync)
        {
            if (_runs.Values.FirstOrDefault(run => run.RequestId == request.RequestId) is { } byRequest)
                return byRequest;

            if (string.IsNullOrWhiteSpace(request.SourceItemId))
                return null;

            return _runs.Values.FirstOrDefault(run =>
                run.Source.Kind == request.Source.Kind &&
                string.Equals(
                    run.SourceItemId,
                    request.SourceItemId,
                    StringComparison.Ordinal));
        }
    }

    public CommandRun Add(CommandRun run)
    {
        lock (_sync)
        {
            if (_runs.ContainsKey(run.RunId))
                throw new InvalidOperationException($"Assistant run {run.RunId} already exists.");

            _runs.Add(run.RunId, run);
            PersistLocked();
            return run;
        }
    }

    public CommandRun? Get(Guid runId)
    {
        lock (_sync)
            return _runs.GetValueOrDefault(runId);
    }

    public IReadOnlyList<CommandRun> GetAll()
    {
        lock (_sync)
            return _runs.Values
                .OrderByDescending(run => run.UpdatedAt)
                .ToList();
    }

    public IReadOnlyList<CommandRun> GetByState(params RunState[] states)

    {
        var wanted = states.ToHashSet();
        lock (_sync)
            return _runs.Values
                .Where(run => wanted.Contains(run.State))
                .OrderBy(run => run.IntendedAt)
                .ToList();
    }

    public CommandRun Update(
        Guid runId,
        Func<CommandRun, CommandRun> update)
    {
        lock (_sync)
        {
            if (!_runs.TryGetValue(runId, out var existing))
                throw new KeyNotFoundException($"Assistant run {runId} was not found.");

            var updated = update(existing);
            _runs[runId] = updated;
            PersistLocked();
            return updated;
        }
    }

    public bool TryCancel(Guid runId, DateTimeOffset now, out CommandRun? updated)
    {

        lock (_sync)
        {
            if (!_runs.TryGetValue(runId, out var existing) ||
                existing.State is RunState.Succeeded or RunState.Failed or RunState.Cancelled)
            {
                updated = existing;
                return false;
            }

            updated = existing with
            {
                State = RunState.Cancelled,
                Reason = "Cancelled by user.",
                UpdatedAt = now
            };
            _runs[runId] = updated;
            PersistLocked();
            return true;
        }
    }

    public DateTimeOffset? NextScheduledAt()
    {
        lock (_sync)
            return _runs.Values
                .Where(run => run.State == RunState.Scheduled)
                .Select(run => (DateTimeOffset?)run.IntendedAt)
                .Min();

    }

    private Dictionary<Guid, CommandRun> Load()
    {
        if (!File.Exists(_storePath))
            return new Dictionary<Guid, CommandRun>();

        try
        {
            var json = File.ReadAllText(_storePath);
            var runs = JsonSerializer.Deserialize<List<CommandRun>>(json)
                ?? new List<CommandRun>();
            return runs.ToDictionary(run => run.RunId);
        }
        catch (Exception ex)
        {
            var corruptPath =
                _storePath + $".corrupt-{DateTimeOffset.Now:yyyyMMdd-HHmmss}";
            try
            {
                File.Move(_storePath, corruptPath, overwrite: false);
            }
            catch (Exception moveError)
            {
                _logger.LogError(
                    moveError,
                    "[Assistant] Could not move corrupt run store {StorePath}.",
                    _storePath);
            }

            _logger.LogError(
                ex,
                "[Assistant] Run store was corrupt and was moved aside to {CorruptPath}.",
                corruptPath);
            return new Dictionary<Guid, CommandRun>();
        }
    }

    private void PersistLocked()
    {
        try
        {
            var json = JsonSerializer.Serialize(
                _runs.Values.OrderBy(run => run.ReceivedAt).ToList(),
                new JsonSerializerOptions { WriteIndented = true });
            var tempPath = _storePath + ".tmp";
            File.WriteAllText(tempPath, json);
            File.Move(tempPath, _storePath, overwrite: true);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "[Assistant] Failed to persist run store.");
            throw new IOException(
                "Assistant request could not be durably persisted.",
                ex);
        }
    }
}
