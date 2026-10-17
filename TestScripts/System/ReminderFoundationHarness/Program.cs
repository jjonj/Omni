using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Models;

static void Check(bool condition, string message)
{
    if (!condition)
        throw new Exception(message);
}

static void CheckEqual<T>(T expected, T actual, string message)
{
    if (!EqualityComparer<T>.Default.Equals(expected, actual))
        throw new Exception($"{message}. Expected={expected}; Actual={actual}");
}

Console.WriteLine("Reminder foundation harness starting...");

Check(ReminderTitleParser.TryParse("Omni clean basement", out var task), "Default task parse failed");
CheckEqual(ReminderKind.Task, task!.Kind, "Default kind");
CheckEqual("clean basement", task.Title, "Default title");

Check(ReminderTitleParser.TryParse("Omni: Reminder - call dentist", out var reminder), "Reminder parse failed");
CheckEqual(ReminderKind.Reminder, reminder!.Kind, "Reminder kind");
CheckEqual("call dentist", reminder.Title, "Reminder title");

Check(ReminderTitleParser.TryParse("Omni alarm leave now", out var alarm), "Alarm parse failed");
CheckEqual(ReminderKind.Alarm, alarm!.Kind, "Alarm kind");
Check(!ReminderTitleParser.TryParse("ordinary calendar event", out _), "Non-Omni title imported");


var ics = """
BEGIN:VCALENDAR
BEGIN:VEVENT
UID:event-abc@google.com
SEQUENCE:4
LAST-MODIFIED:20260927T180000Z
DTSTART;TZID=Europe/Copenhagen:20260928T180000
SUMMARY:Omni Reminder Call dentist
DESCRIPTION:Bring papers\nAnd ID
END:VEVENT
END:VCALENDAR
""";

var parsedEvents = CalendarIcsParser.Parse(ics);
CheckEqual(1, parsedEvents.Count, "ICS event count");
var calendarEvent = parsedEvents[0];
CheckEqual("event-abc@google.com", calendarEvent.Uid, "ICS UID");
CheckEqual(4, calendarEvent.Sequence, "ICS sequence");
CheckEqual("Omni Reminder Call dentist", calendarEvent.Summary, "ICS summary");

var mapped = CalendarReminderMapper.TryMap(calendarEvent);
Check(mapped is not null, "Calendar event did not map");
CheckEqual(ReminderKind.Reminder, mapped!.Kind, "Mapped kind");
CheckEqual("Call dentist", mapped.Title, "Mapped title");
CheckEqual("event-abc@google.com", mapped.SourceEventId, "Mapped source UID");


var storeDir = Path.Combine(Path.GetTempPath(), "OmniReminderHarness", Guid.NewGuid().ToString("N"));
var storePath = Path.Combine(storeDir, "reminders.json");

try
{
    var repository = new ReminderRepository(path: storePath);
    var first = await repository.UpsertMirroredAsync(mapped);
    var second = await repository.UpsertMirroredAsync(mapped);

    CheckEqual(first.Id, second.Id, "Idempotent upsert changed ID");
    CheckEqual(first.Revision, second.Revision, "Idempotent upsert changed revision");

    var claimed = await repository.UpdateAsync(first.Id, current =>
    {
        current.Ownership = ReminderOwnership.Owned;
        current.State = ReminderState.Due;
        current.ClaimedAt = DateTimeOffset.UtcNow;
        return current;
    });
    Check(claimed is not null, "Could not claim reminder");

    mapped.Title = "Late Google rename";
    var lateUpdate = await repository.UpsertMirroredAsync(mapped);
    CheckEqual("Call dentist", lateUpdate.Title, "Owned reminder accepted late Calendar edit");

    var reloaded = new ReminderRepository(path: storePath);
    var loaded = await reloaded.FindBySourceIdentityAsync(mapped.GetSourceIdentity()!);
    Check(loaded is not null, "Reminder did not persist");
    CheckEqual(first.Id, loaded!.Id, "Persisted reminder ID changed");
}
finally
{
    if (Directory.Exists(storeDir))
        Directory.Delete(storeDir, true);
}

Console.WriteLine("Reminder foundation harness passed.");
