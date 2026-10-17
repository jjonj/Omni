using System.Runtime.InteropServices;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Hosting;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Models;
using OmniSync.Hub.Presentation.Hubs;

namespace OmniSync.Hub.Logic.Services;

public sealed record ReminderPresentation(ReminderItem Item, Func<string, Task> Act);

public sealed class ReminderAttentionService(
    IReminderRepository repository,
    IHubContext<RpcApiHub> hub,
    HubMonitorService monitor,
    HubSettingsService settings) : BackgroundService
{
    private string _lastSnapshot = "";

    public static DateTimeOffset NextWindow(DateTimeOffset after, string times)
    {
        var local = after.LocalDateTime;
        var parsed = times.Split(',').Select(value => TimeOnly.TryParse(value.Trim(), out var time) ? time : (TimeOnly?)null)
            .Where(time => time.HasValue).Select(time => time!.Value).ToList();
        if (parsed.Count == 0) parsed.Add(new TimeOnly(11, 0));
        return parsed.SelectMany(time => new[] { local.Date + time.ToTimeSpan(), local.Date.AddDays(1) + time.ToTimeSpan() })
            .Where(date => date > local).Select(date => new DateTimeOffset(date)).Min();
    }

    public static bool ShouldPresent(ReminderItem item, DateTimeOffset now, string times) =>
        !item.IsTerminal && (item.NextFireAt ?? item.DueAt) <= now &&
        (item.LastPresentedAt == null || item.LastPresentedAt < (item.NextFireAt ?? item.DueAt) ||
            (item.Kind == ReminderKind.Task && NextWindow(item.LastPresentedAt.Value, times) <= now));

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5));
        do
        {
            try
            {
                if (CalendarReminderOwnership.RustOwnsBackgroundWork)
                    continue;
                var items = await repository.GetAllAsync(stoppingToken);
                if (CalendarReminderOwnership.RustOwnsBackgroundWork)
                    continue;
                var stamp = string.Join(';', items.OrderBy(item => item.Id).Select(item => $"{item.Id}:{item.Revision}"));
                if (!CalendarReminderOwnership.RustOwnsBackgroundWork && stamp != _lastSnapshot)
                {
                    await hub.Clients.All.SendAsync("RemindersChanged", stoppingToken);
                    monitor.PublishReminderSnapshot(items);
                    monitor.AddLogMessage($"[Reminders] Published {items.Count} records to clients after canonical changes.");
                    _lastSnapshot = stamp;
                }
                if (CalendarReminderOwnership.RustOwnsBackgroundWork || IsPresentationBlocked()) continue;
                var now = DateTimeOffset.Now;
                foreach (var item in items.Where(item => ShouldPresent(item, now, settings.Settings.TaskResurfacingTimes)))
                {
                    if (CalendarReminderOwnership.RustOwnsBackgroundWork)
                        break;
                    var current = await repository.UpdateAsync(item.Id, value =>
                    {
                        if (ShouldPresent(value, now, settings.Settings.TaskResurfacingTimes)) value.LastPresentedAt = now;
                        return value;
                    }, stoppingToken);
                    if (current == null || current.IsTerminal || (current.NextFireAt ?? current.DueAt) > now) continue;
                    monitor.AddLogMessage($"[Reminders] PC presenting {current.Id}: {current.Kind} {current.Title}, revision={current.Revision}");
                    if (!CalendarReminderOwnership.RustOwnsBackgroundWork)
                        monitor.RequestReminderPresentation(new(current, action => ActAsync(current.Id, action)));
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception ex) { monitor.AddLogMessage($"[Reminders] Attention/delivery failed: {ex}"); }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    private async Task ActAsync(Guid id, string action)
    {
        if (CalendarReminderOwnership.RustOwnsBackgroundWork)
            return;
        await repository.UpdateAsync(id, item =>
        {
            if (item.IsTerminal) return item;
            item.Ownership = ReminderOwnership.Owned;
            item.AcknowledgedAt = DateTimeOffset.Now;
            if (action == "snooze") { item.State = ReminderState.SnoozedOrPostponed; item.NextFireAt = DateTimeOffset.Now.AddMinutes(10); }
            else if (action == "tomorrow") { item.State = ReminderState.SnoozedOrPostponed; item.NextFireAt = DateTimeOffset.Now.AddDays(1); }
            else { item.State = item.Kind == ReminderKind.Task ? ReminderState.Done : ReminderState.Dismissed; item.NextFireAt = null; }
            return item;
        });
        monitor.AddLogMessage($"[Reminders] PC action {action} for {id} saved; clients will receive the new revision.");
    }

    private static bool IsPresentationBlocked()
    {
        if (SHQueryUserNotificationState(out var state) == 0 && state is 1 or 2 or 3 or 4 or 7) return true;
        var hwnd = GetForegroundWindow();
        var name = new System.Text.StringBuilder(256);
        GetClassName(hwnd, name, name.Capacity);
        if (name.ToString() is "Progman" or "WorkerW") return false;
        if (!GetWindowRect(hwnd, out var rect)) return false;
        var screen = System.Windows.Forms.Screen.FromHandle(hwnd).Bounds;
        return rect.Left <= screen.Left && rect.Top <= screen.Top && rect.Right >= screen.Right && rect.Bottom >= screen.Bottom;
    }
    [StructLayout(LayoutKind.Sequential)] private struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("shell32.dll")] private static extern int SHQueryUserNotificationState(out int state);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hwnd, System.Text.StringBuilder name, int length);
}
