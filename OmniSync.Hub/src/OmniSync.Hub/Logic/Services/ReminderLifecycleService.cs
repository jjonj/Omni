using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Models;

namespace OmniSync.Hub.Logic.Services;

public sealed class ReminderLifecycleService : BackgroundService
{
    private static readonly TimeSpan TickInterval = TimeSpan.FromSeconds(10);

    private readonly IReminderRepository _repository;
    private readonly ILogger<ReminderLifecycleService> _logger;
    private readonly OmniSync.Hub.Logic.Monitoring.HubMonitorService? _monitor;

    public ReminderLifecycleService(
        IReminderRepository repository,
        ILogger<ReminderLifecycleService> logger,
        OmniSync.Hub.Logic.Monitoring.HubMonitorService? monitor = null)
    {
        _repository = repository;
        _logger = logger;
        _monitor = monitor;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TickInterval);

        if (!CalendarReminderOwnership.RustOwnsBackgroundWork)
            await ClaimDueItemsAsync(DateTimeOffset.Now, stoppingToken);

        while (await timer.WaitForNextTickAsync(stoppingToken))
            if (!CalendarReminderOwnership.RustOwnsBackgroundWork)
                await ClaimDueItemsAsync(DateTimeOffset.Now, stoppingToken);
    }

    internal async Task<int> ClaimDueItemsAsync(
        DateTimeOffset now,
        CancellationToken cancellationToken = default)
    {
        if (CalendarReminderOwnership.RustOwnsBackgroundWork)
            return 0;
        var items = await _repository.GetAllAsync(cancellationToken);
        var claimed = 0;

        foreach (var item in items)
        {
            if (CalendarReminderOwnership.RustOwnsBackgroundWork)
                break;
            if (item.IsTerminal ||
                item.State is not (ReminderState.Pending or ReminderState.SnoozedOrPostponed))
                continue;

            var fireAt = item.NextFireAt ?? item.DueAt;
            if (fireAt > now)
                continue;

            var updated = await _repository.UpdateAsync(item.Id, current =>
            {
                if (current.IsTerminal ||
                    current.State is not (ReminderState.Pending or ReminderState.SnoozedOrPostponed))
                    return current;

                var currentFireAt = current.NextFireAt ?? current.DueAt;
                if (currentFireAt > now)
                    return current;

                current.State = ReminderState.Due;
                current.Ownership = ReminderOwnership.Owned;
                current.ClaimedAt ??= now;
                return current;
            }, cancellationToken);

            if (updated?.State == ReminderState.Due)
            {
                claimed++;
                _monitor?.AddLogMessage($"[Reminders] Due claim {updated.Id}: kind={updated.Kind}, revision={updated.Revision}, fire={(updated.NextFireAt ?? updated.DueAt):o}.");
                _logger.LogInformation(
                    "Reminder {ReminderId} is due: {Kind} {Title}",
                    updated.Id,
                    updated.Kind,
                    updated.Title);
            }
        }

        return claimed;
    }
}
