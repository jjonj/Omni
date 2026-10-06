using System.IO;
using System.Text.Json;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed record ScheduledAssistantCommand(
    Guid Id,
    string CommandId,
    Dictionary<string, string> Arguments,
    DateTimeOffset RunAt,
    DateTimeOffset CreatedAt);

public sealed class AssistantCommandScheduler : BackgroundService
{
    private readonly AssistantCommandRegistry _registry;
    private readonly HubMonitorService _monitor;
    private readonly ILogger<AssistantCommandScheduler> _logger;
    private readonly string _storePath;
    private readonly object _sync = new();
    private readonly List<ScheduledAssistantCommand> _pending;

    public AssistantCommandScheduler(
        AssistantCommandRegistry registry,
        HubMonitorService monitor,
        ILogger<AssistantCommandScheduler> logger)
    {
        _registry = registry;
        _monitor = monitor;
        _logger = logger;

        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "OmniSync");
        Directory.CreateDirectory(dir);
        _storePath = Path.Combine(dir, "assistant_schedule.json");
        _pending = LoadFromDisk();
    }

    public Guid Schedule(
        AssistantCommandMatch match,
        DateTimeOffset runAt)
    {
        var item = new ScheduledAssistantCommand(
            Guid.NewGuid(),
            match.Command.Descriptor.Id,
            match.Arguments.ToDictionary(
                pair => pair.Key,
                pair => pair.Value,
                StringComparer.OrdinalIgnoreCase),
            runAt,
            DateTimeOffset.Now);

        lock (_sync)
        {
            _pending.Add(item);
            PersistLocked();
        }

        _monitor.AddLogMessage(
            $"[Assistant] Scheduled {item.CommandId} for {item.RunAt.LocalDateTime:g}");
        return item.Id;
    }

    protected override async Task ExecuteAsync(
        CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            foreach (var item in TakeDue(DateTimeOffset.Now))
                await ExecuteScheduledAsync(item, stoppingToken);

            try
            {
                await Task.Delay(TimeSpan.FromSeconds(1), stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
        }
    }

    private List<ScheduledAssistantCommand> TakeDue(DateTimeOffset now)
    {
        lock (_sync)
        {
            var due = _pending
                .Where(item => item.RunAt <= now)
                .OrderBy(item => item.RunAt)
                .ToList();

            if (due.Count == 0)
                return due;

            var dueIds = due.Select(item => item.Id).ToHashSet();
            _pending.RemoveAll(item => dueIds.Contains(item.Id));
            PersistLocked();
            return due;
        }
    }

    private async Task ExecuteScheduledAsync(
        ScheduledAssistantCommand item,
        CancellationToken cancellationToken)
    {
        if (!_registry.TryGet(item.CommandId, out var command))
        {
            _monitor.AddLogMessage(
                $"[Assistant] Skipped scheduled command {item.CommandId}: command no longer exists.");
            return;
        }

        var maxLateness = command.Descriptor.QueuePolicy.MaxLateness;
        if (maxLateness is not null &&
            DateTimeOffset.Now - item.RunAt > maxLateness.Value)
        {
            _monitor.AddLogMessage(
                $"[Assistant] Skipped stale scheduled command {item.CommandId}.");
            return;
        }

        try
        {
            var match = new AssistantCommandMatch(command, item.Arguments);
            var result = await _registry.ExecuteAsync(match, cancellationToken);
            _monitor.AddLogMessage(
                $"[Assistant] Scheduled {item.CommandId}: {result.Summary}");
        }
        catch (Exception ex)
        {
            _logger.LogError(
                ex,
                "[Assistant] Scheduled command {CommandId} failed",
                item.CommandId);
            _monitor.AddLogMessage(
                $"[Assistant] Scheduled {item.CommandId} failed: {ex.Message}");
        }
    }

    private List<ScheduledAssistantCommand> LoadFromDisk()
    {
        try
        {
            if (!File.Exists(_storePath))
                return new List<ScheduledAssistantCommand>();

            var json = File.ReadAllText(_storePath);
            return JsonSerializer.Deserialize<List<ScheduledAssistantCommand>>(json)
                ?? new List<ScheduledAssistantCommand>();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "[Assistant] Could not load scheduled commands.");
            return new List<ScheduledAssistantCommand>();
        }
    }

    private void PersistLocked()
    {
        try
        {
            var json = JsonSerializer.Serialize(
                _pending,
                new JsonSerializerOptions { WriteIndented = true });
            var temp = _storePath + ".tmp";
            File.WriteAllText(temp, json);
            File.Move(temp, _storePath, true);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "[Assistant] Could not persist scheduled commands.");
        }
    }
}
