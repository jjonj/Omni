using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Models;

namespace OmniSync.Hub.Logic.Services;

public sealed class CalendarReminderSyncService : IHostedService, IDisposable
{
    private static readonly TimeSpan MissingEventCancellationHorizon = TimeSpan.FromDays(366);

    private readonly CalendarService _calendarService;
    private readonly IReminderRepository _repository;
    private readonly ILogger<CalendarReminderSyncService> _logger;
    private readonly SemaphoreSlim _syncGate = new(1, 1);
    private bool _started;

    public CalendarReminderSyncService(
        CalendarService calendarService,
        IReminderRepository repository,
        ILogger<CalendarReminderSyncService> logger)
    {
        _calendarService = calendarService;
        _repository = repository;
        _logger = logger;
    }

    public async Task StartAsync(CancellationToken cancellationToken)
    {
        if (_started)
            return;

        _started = true;
        _calendarService.Refreshed += OnCalendarRefreshed;

        // Ensure an initial import even if CalendarService's startup timer raced us.
        await _calendarService.RefreshCalendarAsync();
        await SyncCurrentSnapshotAsync(cancellationToken);
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        if (_started)
        {
            _calendarService.Refreshed -= OnCalendarRefreshed;
            _started = false;
        }

        return Task.CompletedTask;
    }

    private void OnCalendarRefreshed(object? sender, EventArgs e)
    {
        _ = SyncCurrentSnapshotAsync(CancellationToken.None);
    }

    internal async Task SyncCurrentSnapshotAsync(CancellationToken cancellationToken)
    {
        if (!await _syncGate.WaitAsync(0, cancellationToken))
            return;

        try
        {
            var snapshot = _calendarService.GetEventsSnapshot();
            var seenSourceIdentities = new HashSet<string>(StringComparer.Ordinal);
            var activeSourceIdentities = new HashSet<string>(StringComparer.Ordinal);

            foreach (var calendarEvent in snapshot)
            {
                var sourceIdentity = CalendarReminderMapper.GetSourceIdentity(calendarEvent);
                if (sourceIdentity is not null)
                    seenSourceIdentities.Add(sourceIdentity);

                var reminder = CalendarReminderMapper.TryMap(calendarEvent);
                if (reminder is null)
                    continue;

                activeSourceIdentities.Add(reminder.GetSourceIdentity()!);
                await _repository.UpsertMirroredAsync(reminder, cancellationToken);
            }

            await CancelRemovedOrRenamedPendingItemsAsync(
                seenSourceIdentities,
                activeSourceIdentities,
                cancellationToken);

            _logger.LogDebug(
                "Calendar reminder sync complete: {ActiveCount} active Omni reminders from {CalendarCount} calendar events.",
                activeSourceIdentities.Count,
                snapshot.Count);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Calendar reminder synchronization failed.");
        }
        finally
        {
            _syncGate.Release();
        }
    }

    private async Task CancelRemovedOrRenamedPendingItemsAsync(
        HashSet<string> seenSourceIdentities,
        HashSet<string> activeSourceIdentities,
        CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.Now;
        var cancellationLimit = now.Add(MissingEventCancellationHorizon);
        var items = await _repository.GetAllAsync(cancellationToken);

        foreach (var item in items)
        {
            if (item.Source != ReminderSource.GoogleCalendar ||
                item.Ownership != ReminderOwnership.Mirroring ||
                item.IsTerminal)
                continue;

            var sourceIdentity = item.GetSourceIdentity();
            if (sourceIdentity is null || activeSourceIdentities.Contains(sourceIdentity))
                continue;

            // If the event is still present but no longer parses as Omni, cancel it.
            // If it disappeared, only infer deletion inside our known recurrence horizon.
            var definitelyRemovedOrRenamed =
                seenSourceIdentities.Contains(sourceIdentity) ||
                (item.DueAt >= now.AddDays(-1) && item.DueAt <= cancellationLimit);

            if (!definitelyRemovedOrRenamed)
                continue;

            await _repository.UpdateAsync(item.Id, current =>
            {
                if (current.Ownership == ReminderOwnership.Mirroring && !current.IsTerminal)
                    current.State = ReminderState.Cancelled;
                return current;
            }, cancellationToken);
        }
    }

    public void Dispose()
    {
        if (_started)
            _calendarService.Refreshed -= OnCalendarRefreshed;

        _syncGate.Dispose();
    }
}
