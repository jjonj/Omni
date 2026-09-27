using OmniSync.Hub.Models;

namespace OmniSync.Hub.Logic.Services;

public static class CalendarReminderMapper
{
    public const string DefaultCalendarSourceId = "configured-ics";
    private static readonly TimeSpan DefaultAllDayTaskTime = TimeSpan.FromHours(11);

    public static ReminderItem? TryMap(
        CalendarEvent calendarEvent,
        string calendarSourceId = DefaultCalendarSourceId)
    {
        ArgumentNullException.ThrowIfNull(calendarEvent);

        if (string.IsNullOrWhiteSpace(calendarEvent.Uid))
            return null;

        if (string.Equals(
                calendarEvent.Status,
                "CANCELLED",
                StringComparison.OrdinalIgnoreCase))
            return null;

        if (!ReminderTitleParser.TryParse(calendarEvent.Summary, out var parsed))
            return null;

        var dueLocal = calendarEvent.IsAllDay
            ? calendarEvent.Start.Date.Add(DefaultAllDayTaskTime)
            : calendarEvent.Start;

        if (dueLocal.Kind == DateTimeKind.Unspecified)
            dueLocal = DateTime.SpecifyKind(dueLocal, DateTimeKind.Local);

        var isRecurring =
            !string.IsNullOrWhiteSpace(calendarEvent.RRule) ||
            !string.IsNullOrWhiteSpace(calendarEvent.RecurrenceId);

        var occurrenceKey = isRecurring
            ? calendarEvent.RecurrenceId ??
              dueLocal.ToUniversalTime().ToString("O")
            : null;

        return new ReminderItem
        {
            Source = ReminderSource.GoogleCalendar,
            SourceCalendarId = calendarSourceId,
            SourceEventId = calendarEvent.Uid,
            SourceOccurrenceKey = occurrenceKey,
            SourceVersion = $"{calendarEvent.Sequence}:{calendarEvent.LastModified}",
            SourceOriginalTitle = calendarEvent.Summary,
            RecurrenceSeriesId = isRecurring ? calendarEvent.Uid : null,
            RecurrenceRule = calendarEvent.RRule,
            Title = parsed!.Title,
            Kind = calendarEvent.IsAllDay ? ReminderKind.Task : parsed.Kind,
            State = ReminderState.Pending,
            Ownership = ReminderOwnership.Mirroring,
            DueAt = new DateTimeOffset(dueLocal),
            NextFireAt = new DateTimeOffset(dueLocal),
            Notes = calendarEvent.Description,
            IsAllDay = calendarEvent.IsAllDay
        };
    }

    public static string? GetSourceIdentity(
        CalendarEvent calendarEvent,
        string calendarSourceId = DefaultCalendarSourceId)

    {
        if (string.IsNullOrWhiteSpace(calendarEvent.Uid))
            return null;

        var isRecurring =
            !string.IsNullOrWhiteSpace(calendarEvent.RRule) ||
            !string.IsNullOrWhiteSpace(calendarEvent.RecurrenceId);

        string? occurrenceKey = null;
        if (isRecurring)
        {
            occurrenceKey = calendarEvent.RecurrenceId;
            if (string.IsNullOrWhiteSpace(occurrenceKey))
            {
                var start = calendarEvent.Start.Kind == DateTimeKind.Unspecified
                    ? DateTime.SpecifyKind(calendarEvent.Start, DateTimeKind.Local)
                    : calendarEvent.Start;
                occurrenceKey = start.ToUniversalTime().ToString("O");
            }
        }

        var identity = new ReminderItem
        {
            Source = ReminderSource.GoogleCalendar,
            SourceCalendarId = calendarSourceId,
            SourceEventId = calendarEvent.Uid,
            SourceOccurrenceKey = occurrenceKey
        };

        return identity.GetSourceIdentity();
    }
}
