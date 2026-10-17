using System.Collections.Concurrent;
using System.Threading.Channels;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class AssistantCommandDispatcherService : BackgroundService
{
    private readonly AssistantRunStore _store;
    private readonly AssistantCommandCatalog _catalog;
    private readonly AssistantRunEventSink _events;
    private readonly AssistantRemoteDispatcher _remoteDispatcher;
    private readonly TimeProvider _timeProvider;
    private readonly ILogger<AssistantCommandDispatcherService> _logger;
    private readonly Channel<Guid> _queue = Channel.CreateUnbounded<Guid>();
    private readonly ConcurrentDictionary<Guid, Task> _running = new();
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _concurrency =
        new(StringComparer.OrdinalIgnoreCase);

    public AssistantCommandDispatcherService(
        AssistantRunStore store,
        AssistantCommandCatalog catalog,
        AssistantRunEventSink events,
        AssistantRemoteDispatcher remoteDispatcher,
        TimeProvider timeProvider,
        ILogger<AssistantCommandDispatcherService> logger)
    {
        _store = store;
        _catalog = catalog;
        _events = events;
        _remoteDispatcher = remoteDispatcher;
        _timeProvider = timeProvider;
        _logger = logger;
    }

    public void Enqueue(Guid runId)
    {
        _queue.Writer.TryWrite(runId);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await RecoverAsync();

        foreach (var run in _store.GetByState(RunState.Accepted))
            Enqueue(run.RunId);

        try
        {
            while (!stoppingToken.IsCancellationRequested)
            {
                await PromoteDueScheduledAsync();

                while (_queue.Reader.TryRead(out var runId))
                {
                    if (_running.ContainsKey(runId))
                        continue;

                    var task = ExecuteOneSafeAsync(runId, stoppingToken);
                    if (_running.TryAdd(runId, task))
                    {
                        _ = task.ContinueWith(
                            _ => _running.TryRemove(runId, out _),
                            CancellationToken.None,
                            TaskContinuationOptions.ExecuteSynchronously,
                            TaskScheduler.Default);
                    }
                }

                var now = _timeProvider.GetUtcNow();
                var next = _store.NextScheduledAt();
                var delay = next is null
                    ? TimeSpan.FromMinutes(5)
                    : next.Value <= now
                        ? TimeSpan.Zero
                        : next.Value - now;

                var queued = _queue.Reader.WaitToReadAsync(stoppingToken).AsTask();
                var timer = Task.Delay(delay, _timeProvider, stoppingToken);
                await Task.WhenAny(queued, timer);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
        }

        try
        {
            await Task.WhenAll(_running.Values);
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task RecoverAsync()
    {
        var now = _timeProvider.GetUtcNow();
        foreach (var run in _store.GetByState(RunState.Running))
        {
            var canRetry =
                run.CommandId is not null &&
                _catalog.TryGet(run.CommandId, out var command) &&
                command.Descriptor.Execution.Idempotent;

            var recovered = _store.Update(run.RunId, current => current with
            {
                State = canRetry ? RunState.Accepted : RunState.Interrupted,
                Reason = canRetry
                    ? "Recovered after Hub restart; safe to retry."
                    : "Interrupted by Hub shutdown or crash.",
                UpdatedAt = now
            });
            await _events.PublishAsync(recovered);

            if (canRetry)
                Enqueue(run.RunId);
        }
    }

    private async Task PromoteDueScheduledAsync()
    {
        var now = _timeProvider.GetUtcNow();
        var due = _store.GetByState(RunState.Scheduled)
            .Where(run =>
                run.IntendedAt <= now &&
                !AssistantHostSelector.IsRemote(run.HostId))
            .ToList();

        foreach (var run in due)
        {
            var accepted = _store.Update(run.RunId, current => current with
            {
                State = RunState.Accepted,
                Reason = null,
                UpdatedAt = now
            });
            await _events.PublishAsync(accepted);
            Enqueue(run.RunId);
        }
    }

    private async Task ExecuteOneSafeAsync(
        Guid runId,
        CancellationToken stoppingToken)
    {
        try
        {
            await ExecuteOneAsync(runId, stoppingToken);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "[Assistant] Dispatcher failed for run {RunId}.", runId);
            var existing = _store.Get(runId);
            if (existing is null ||
                existing.State is RunState.Succeeded or RunState.Failed or RunState.Cancelled)
                return;

            var failed = _store.Update(runId, current => current with
            {
                State = RunState.Failed,
                Reason = ex.Message,
                Summary = ex.Message,
                UpdatedAt = _timeProvider.GetUtcNow()
            });
            await _events.PublishAsync(failed);
        }
    }

    private async Task ExecuteOneAsync(
        Guid runId,
        CancellationToken stoppingToken)
    {
        var run = _store.Get(runId);
        if (run is null || run.State != RunState.Accepted)
            return;

        if (run.CommandId is null || !_catalog.TryGet(run.CommandId, out var command))
        {
            await FinishAsync(runId, RunState.Failed, "Command no longer exists.", "Command no longer exists.");
            return;
        }

        if (run.CommandVersion != command.Descriptor.Version)
        {
            await FinishAsync(
                runId,
                RunState.Failed,
                "Command version changed before execution.",
                $"Cannot run {run.CommandId}: stored version {run.CommandVersion}, current version {command.Descriptor.Version}.");
            return;
        }

        var now = _timeProvider.GetUtcNow();
        var maxDelay = command.Descriptor.Staleness.MaxDelay;
        if (maxDelay is not null &&
            now > run.IntendedAt &&
            now - run.IntendedAt > maxDelay.Value)
        {
            await FinishAsync(
                runId,
                RunState.Skipped,
                $"Stale by {(now - run.IntendedAt):g}.",
                $"Skipped stale {run.CommandId}.");
            return;
        }

        if (AssistantHostSelector.IsRemote(run.HostId))
        {
            var scheduleRemotely = run.IntendedAt > now.AddMilliseconds(500);
            var dispatched = await _remoteDispatcher.DispatchAsync(
                run,
                scheduleRemotely,
                stoppingToken);
            if (!dispatched)
            {
                await FinishAsync(
                    runId,
                    RunState.Failed,
                    "The selected remote host is no longer connected.",
                    $"No available host for {run.CommandId}.");
                return;
            }

            // A scheduled remote run is not considered durable until the device
            // persists it and reports the "scheduled" acknowledgement.
            var remoteState = RunState.Running;
            var remoteSummary = scheduleRemotely
                ? $"Sent {run.CommandId} scheduling request to {run.HostId}; awaiting device acknowledgement."
                : $"Dispatched {run.CommandId} to {run.HostId}.";
            run = _store.Update(runId, current => current with
            {
                State = remoteState,
                Reason = null,
                Summary = remoteSummary,
                UpdatedAt = now
            });
            await _events.PublishAsync(run);
            return;
        }

        run = _store.Update(runId, current => current with
        {
            State = RunState.Running,
            Reason = null,
            UpdatedAt = now
        });
        await _events.PublishAsync(run);

        var concurrencyKey = command.Descriptor.Execution.ConcurrencyKey;
        SemaphoreSlim? concurrencyGate = null;
        if (!string.IsNullOrWhiteSpace(concurrencyKey))
        {
            concurrencyGate = _concurrency.GetOrAdd(
                concurrencyKey,
                _ => new SemaphoreSlim(1, 1));
        }

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
        timeout.CancelAfter(command.Descriptor.Execution.Timeout);
        var gateHeld = false;

        try
        {
            if (concurrencyGate is not null)
            {
                await concurrencyGate.WaitAsync(timeout.Token);
                gateHeld = true;
            }

            run = _store.Get(runId) ?? run;
            if (run.State == RunState.Cancelled)
                return;

            var request = new AssistantRequest(
                run.RequestId,
                run.OriginalText,
                run.Source,
                run.SourceItemId,
                run.OccurredAt,
                run.ReceivedAt,
                run.ConversationId,
                run.DryRun);
            var outcome = await command.ExecuteAsync(
                new AssistantCommandInvocation(run, request),
                timeout.Token);

            switch (outcome)
            {
                case AssistantCommandOutcome.Succeeded succeeded:
                    await FinishAsync(runId, RunState.Succeeded, null, succeeded.Summary);
                    break;
                case AssistantCommandOutcome.Failed failed:
                    await FinishAsync(runId, RunState.Failed, failed.Summary, failed.Summary);
                    break;
                case AssistantCommandOutcome.NeedsClarification clarification:
                    await FinishAsync(
                        runId,
                        RunState.NeedsClarification,
                        clarification.Question,
                        clarification.Question);
                    break;
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            await FinishAsync(
                runId,
                RunState.Interrupted,
                "Hub is stopping.",
                $"Interrupted {run.CommandId}.");
        }

        catch (OperationCanceledException)
        {
            await FinishAsync(
                runId,
                RunState.Failed,
                $"Timed out after {command.Descriptor.Execution.Timeout}.",
                $"{run.CommandId} timed out.");
        }
        finally
        {
            if (gateHeld)
                concurrencyGate!.Release();
        }
    }

    private async Task FinishAsync(
        Guid runId,
        RunState state,
        string? reason,
        string? summary)
    {
        var current = _store.Get(runId);
        if (current is null || current.State == RunState.Cancelled)
            return;

        var updated = _store.Update(runId, run => run with
        {
            State = state,
            Reason = reason,
            Summary = summary,
            UpdatedAt = _timeProvider.GetUtcNow()
        });
        await _events.PublishAsync(updated);
    }
}
