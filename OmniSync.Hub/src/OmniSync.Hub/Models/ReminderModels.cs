using System.Text.Json.Serialization;

namespace OmniSync.Hub.Models;

public enum ReminderKind
{
    Task,
    Reminder,
    Alarm
}

public enum ReminderState
{
    Pending,
    Due,
    SnoozedOrPostponed,
    Done,
    Dismissed,
    Cancelled
}

public enum ReminderSource
{
    Local,
    GoogleCalendar
}

public enum ReminderOwnership
{
    Mirroring,
    Owned
}

public sealed class ReminderItem
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public ReminderKind Kind { get; set; } = ReminderKind.Task;
    public ReminderState State { get; set; } = ReminderState.Pending;
    public ReminderOwnership Ownership { get; set; } = ReminderOwnership.Mirroring;
    public string Title { get; set; } = string.Empty;
    public DateTimeOffset DueAt { get; set; }
    public DateTimeOffset? NextFireAt { get; set; }
    public long Revision { get; set; } = 1;

    public ReminderSource Source { get; set; } = ReminderSource.Local;
    public string? SourceCalendarId { get; set; }
    public string? SourceEventId { get; set; }
    public string? SourceOccurrenceKey { get; set; }
    public string? SourceVersion { get; set; }
    public string? SourceOriginalTitle { get; set; }
    public string? RecurrenceSeriesId { get; set; }
    public string? RecurrenceRule { get; set; }
    public string? Notes { get; set; }
    public bool IsAllDay { get; set; }

    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset? ClaimedAt { get; set; }
    public DateTimeOffset? AcknowledgedAt { get; set; }
    public DateTimeOffset? CompletedAt { get; set; }
    public DateTimeOffset? LastPresentedAt { get; set; }

    [JsonIgnore]
    public bool IsTerminal =>
        State is ReminderState.Done or ReminderState.Dismissed or ReminderState.Cancelled;

    public string? GetSourceIdentity()
    {
        if (Source == ReminderSource.Local || string.IsNullOrWhiteSpace(SourceEventId))
            return null;

        return string.Join("|",
            SourceCalendarId ?? string.Empty,
            SourceEventId,
            SourceOccurrenceKey ?? string.Empty);
    }

    public ReminderItem Copy() => (ReminderItem)MemberwiseClone();
}
