using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Models;
using Xunit;

namespace OmniSync.Hub.Tests.Logic.Services;

public class ReminderTitleParserTests
{
    [Theory]
    [InlineData("Omni clean basement", ReminderKind.Task, "clean basement")]
    [InlineData("Omni task clean basement", ReminderKind.Task, "clean basement")]
    [InlineData("Omni Reminder take bins out", ReminderKind.Reminder, "take bins out")]
    [InlineData("Omni alarm leave now", ReminderKind.Alarm, "leave now")]
    [InlineData(" Omni: reminder - call dentist ", ReminderKind.Reminder, "call dentist")]
    [InlineData("Omni check smoke alarm", ReminderKind.Task, "check smoke alarm")]
    public void ParsesSupportedTitles(
        string input,
        ReminderKind expectedKind,
        string expectedTitle)
    {
        var ok = ReminderTitleParser.TryParse(input, out var parsed);

        Assert.True(ok);
        Assert.NotNull(parsed);
        Assert.Equal(expectedKind, parsed!.Kind);
        Assert.Equal(expectedTitle, parsed.Title);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("Dentist tomorrow")]
    [InlineData("omnivore task")]
    [InlineData("Omni")]
    public void RejectsNonOmniTitles(string? input)
    {
        Assert.False(ReminderTitleParser.TryParse(input, out var parsed));
        Assert.Null(parsed);
    }
}

public class ReminderRepositoryTests
{
    [Fact]
    public async Task MirroredUpsertIsIdempotentAndDurable()
    {
        var path = TempStorePath();
        try
        {
            var repository = new ReminderRepository(path: path);
            var source = CreateCalendarReminder();

            var created = await repository.UpsertMirroredAsync(source);
            var repeated = await repository.UpsertMirroredAsync(source);

            Assert.Equal(created.Id, repeated.Id);
            Assert.Equal(created.Revision, repeated.Revision);

            var reloadedRepository = new ReminderRepository(path: path);
            var reloaded = await reloadedRepository.FindBySourceIdentityAsync(
                source.GetSourceIdentity()!);

            Assert.NotNull(reloaded);
            Assert.Equal(created.Id, reloaded!.Id);
            Assert.Equal("test event", reloaded.Title);
        }
        finally
        {
            DeleteStore(path);
        }
    }

    [Fact]
    public async Task MirroredChangeAdvancesRevision()
    {
        var path = TempStorePath();
        try
        {
            var repository = new ReminderRepository(path: path);
            var source = CreateCalendarReminder();
            var created = await repository.UpsertMirroredAsync(source);

            source.Title = "renamed event";
            source.SourceOriginalTitle = "Omni renamed event";
            var changed = await repository.UpsertMirroredAsync(source);

            Assert.Equal(created.Id, changed.Id);
            Assert.Equal(created.Revision + 1, changed.Revision);
            Assert.Equal("renamed event", changed.Title);

        }
        finally
        {
            DeleteStore(path);
        }
    }

    [Fact]
    public async Task OwnedOccurrenceIgnoresLateCalendarChanges()
    {
        var path = TempStorePath();
        try
        {
            var repository = new ReminderRepository(path: path);
            var source = CreateCalendarReminder();
            var created = await repository.UpsertMirroredAsync(source);

            var owned = await repository.UpdateAsync(created.Id, item =>
            {
                item.Ownership = ReminderOwnership.Owned;
                item.State = ReminderState.Due;
                item.ClaimedAt = DateTimeOffset.UtcNow;
                return item;
            });

            source.Title = "late google rename";
            source.DueAt = source.DueAt.AddHours(2);
            var afterLateSync = await repository.UpsertMirroredAsync(source);

            Assert.NotNull(owned);
            Assert.Equal(owned!.Revision, afterLateSync.Revision);
            Assert.Equal("test event", afterLateSync.Title);

            Assert.Equal(ReminderOwnership.Owned, afterLateSync.Ownership);
        }
        finally
        {
            DeleteStore(path);
        }
    }

    private static ReminderItem CreateCalendarReminder() => new()
    {
        Source = ReminderSource.GoogleCalendar,
        SourceCalendarId = "primary",
        SourceEventId = "event-123",
        SourceOccurrenceKey = "2026-09-28T18:00:00+02:00",
        SourceVersion = "1",
        SourceOriginalTitle = "Omni test event",
        Title = "test event",
        Kind = ReminderKind.Task,
        DueAt = new DateTimeOffset(2026, 9, 28, 18, 0, 0, TimeSpan.FromHours(2)),
        NextFireAt = new DateTimeOffset(2026, 9, 28, 18, 0, 0, TimeSpan.FromHours(2))
    };

    private static string TempStorePath()
    {
        var directory = Path.Combine(
            Path.GetTempPath(),
            "OmniSyncReminderTests",
            Guid.NewGuid().ToString("N"));
        return Path.Combine(directory, "reminders.json");
    }

    private static void DeleteStore(string path)

    {
        var directory = Path.GetDirectoryName(path);
        if (directory is not null && Directory.Exists(directory))
            Directory.Delete(directory, recursive: true);
    }
}


public class CalendarIcsParserTests
{
    [Fact]
    public void ParsesGoogleStyleIdentityTimezoneAndFoldedText()
    {
        var ics = """
BEGIN:VCALENDAR
BEGIN:VEVENT
UID:event-abc@google.com
SEQUENCE:4
LAST-MODIFIED:20260927T180000Z
DTSTART;TZID=Europe/Copenhagen:20260928T180000
SUMMARY:Omni Reminder Call
 dentist
DESCRIPTION:Bring papers\nAnd ID
END:VEVENT
END:VCALENDAR
""";

        var events = CalendarIcsParser.Parse(ics);

        var calendarEvent = Assert.Single(events);
        Assert.Equal("event-abc@google.com", calendarEvent.Uid);
        Assert.Equal(4, calendarEvent.Sequence);
        Assert.Equal("Omni Reminder Calldentist", calendarEvent.Summary);
        Assert.Equal("Bring papers\nAnd ID", calendarEvent.Description);

        var copenhagen = TimeZoneInfo.FindSystemTimeZoneById("Europe/Copenhagen");
        var sourceLocal = new DateTime(
            2026, 9, 28, 18, 0, 0, DateTimeKind.Unspecified);
        var expectedLocal = TimeZoneInfo
            .ConvertTimeToUtc(sourceLocal, copenhagen)
            .ToLocalTime();

        Assert.Equal(expectedLocal, calendarEvent.Start);
    }
}

public class CalendarReminderMapperTests
{
    [Fact]
    public void MapsDefaultOmniEventToTaskUsingStableUid()
    {
        var calendarEvent = new CalendarEvent
        {
            Uid = "google-123",
            Summary = "Omni Clean basement",
            Start = new DateTime(2026, 9, 28, 18, 0, 0, DateTimeKind.Local),
            Sequence = 2,
            LastModified = "20260927T180000Z"
        };

        var reminder = CalendarReminderMapper.TryMap(calendarEvent);

        Assert.NotNull(reminder);
        Assert.Equal(ReminderKind.Task, reminder!.Kind);
        Assert.Equal("Clean basement", reminder.Title);

        Assert.Equal("google-123", reminder.SourceEventId);
        Assert.Null(reminder.SourceOccurrenceKey);
        Assert.Equal("2:20260927T180000Z", reminder.SourceVersion);
    }

    [Fact]
    public void RecurringEventUsesOccurrenceIdentityAndAllDayForcesTask()
    {
        var calendarEvent = new CalendarEvent
        {
            Uid = "weekly-1",
            Summary = "Omni Alarm Clean garage",
            Start = new DateTime(2026, 10, 3, 0, 0, 0, DateTimeKind.Local),
            IsAllDay = true,
            RRule = "FREQ=WEEKLY"
        };

        var reminder = CalendarReminderMapper.TryMap(calendarEvent);

        Assert.NotNull(reminder);
        Assert.Equal(ReminderKind.Task, reminder!.Kind);
        Assert.NotNull(reminder.SourceOccurrenceKey);
        Assert.Equal(new TimeSpan(11, 0, 0), reminder.DueAt.TimeOfDay);
        Assert.Equal("weekly-1", reminder.RecurrenceSeriesId);
    }
}
