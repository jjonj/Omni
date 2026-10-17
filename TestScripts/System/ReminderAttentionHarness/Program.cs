using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Models;

static void Check(bool value, string name)
{
    if (!value) throw new Exception(name);
    Console.WriteLine($"PASS: {name}");
}

var now = DateTimeOffset.Now.Date.AddHours(14);
var clock = new DateTimeOffset(now);
var task = new ReminderItem { Kind = ReminderKind.Task, DueAt = clock.AddHours(-1) };
Check(ReminderAttentionService.ShouldPresent(task, clock, "11:00,18:00"), "first due presentation");
task.LastPresentedAt = clock;
Check(!ReminderAttentionService.ShouldPresent(task, clock.AddHours(1), "11:00,18:00"), "ignored task waits for the next window");
Check(ReminderAttentionService.ShouldPresent(task, clock.AddHours(4), "11:00,18:00"), "ignored task resurfaces at the configured window");
task.State = ReminderState.Done;
Check(!ReminderAttentionService.ShouldPresent(task, clock.AddDays(1), "11:00,18:00"), "completed task never resurfaces");
task.State = ReminderState.SnoozedOrPostponed;
task.NextFireAt = clock.AddHours(2);
Check(!ReminderAttentionService.ShouldPresent(task, clock, "11:00,18:00"), "postpone suppresses the old due presentation");
Check(ReminderAttentionService.ShouldPresent(task, clock.AddHours(2), "11:00,18:00"), "postpone fires at its new due time");
var alarm = new ReminderItem { Kind = ReminderKind.Alarm, DueAt = clock.AddMinutes(-1), LastPresentedAt = clock };
Check(!ReminderAttentionService.ShouldPresent(alarm, clock.AddDays(1), "11:00,18:00"), "already-presented alarm is not repeatedly reopened");
Check(ReminderAttentionService.NextWindow(clock.AddHours(6), "11:00,18:00").Date == clock.AddDays(1).Date, "task window crosses midnight correctly");
Check(ReminderAttentionService.NextWindow(clock, "invalid").Hour == 11, "invalid schedule uses the documented 11:00 fallback");
