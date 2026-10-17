using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Logic.Services
{
    public class CalendarService : IHostedService, IDisposable
    {
        // A reminder created while OmniHub is offline must not disappear just because its due time passed.
        // Keep recent calendar history so the reminder importer can claim missed occurrences on reconnect.
        public static readonly TimeSpan ReminderLookback = TimeSpan.FromHours(48);

        private readonly HttpClient _httpClient;
        private readonly HubMonitorService _monitorService;
        private readonly HubSettingsService _settingsService;
        private readonly ILogger<CalendarService> _logger;
        private System.Threading.Timer? _timer;
        private readonly object _eventsLock = new();
        private List<CalendarEvent> _events = new();
        private string? _lastCalendarUrl;
        private int _isRefreshing = 0;

        public event EventHandler? Refreshed;

        public CalendarService(
            HttpClient httpClient,
            HubMonitorService monitorService,
            HubSettingsService settingsService,
            ILogger<CalendarService> logger)
        {
            _httpClient = httpClient;
            _monitorService = monitorService;
            _settingsService = settingsService;
            _logger = logger;
            _lastCalendarUrl = _settingsService.Settings?.CalendarUrl;

            _settingsService.SettingsChanged += (s, e) =>
            {
                var newUrl = _settingsService.Settings?.CalendarUrl;
                if (!CalendarReminderOwnership.RustOwnsBackgroundWork &&
                    !string.Equals(newUrl, _lastCalendarUrl, StringComparison.OrdinalIgnoreCase))
                {
                    _lastCalendarUrl = newUrl;
                    _ = RefreshCalendarAsync();
                }
            };
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation("CalendarService starting (Rust delegation: {Delegated}).", CalendarReminderOwnership.RustOwnsBackgroundWork);
            // Omni reminders depend on voice corrections being picked up quickly.
            _timer = new System.Threading.Timer(DoWork, null, TimeSpan.Zero, TimeSpan.FromMinutes(1));
            return Task.CompletedTask;
        }

        private async void DoWork(object? state)
        {
            try
            {
                await RefreshCalendarAsync();
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error refreshing calendar.");
            }
        }

        public async Task RefreshCalendarAsync()
        {
            if (CalendarReminderOwnership.RustOwnsBackgroundWork)
                return;

            if (Interlocked.CompareExchange(ref _isRefreshing, 1, 0) != 0)
            {
                _logger.LogInformation("[CalendarService] Refresh already in progress, skipping.");
                return;
            }

            try
            {
                string url = _settingsService.Settings.CalendarUrl;
                if (string.IsNullOrEmpty(url))
                {
                    _logger.LogWarning("[CalendarService] No Calendar URL configured.");
                    return;
                }

                _logger.LogInformation($"[CalendarService] Refreshing from: {url}");
                var icsData = await _httpClient.GetStringAsync(url);
                _logger.LogInformation($"[CalendarService] Received {icsData.Length} bytes of ICS data.");

                // The marker can appear while an HTTP refresh is awaiting I/O.
                // Never publish stale results or trigger a C# mirror sync after delegation.
                if (CalendarReminderOwnership.RustOwnsBackgroundWork)
                    return;

                var events = CalendarIcsParser.Parse(icsData);
                _logger.LogInformation($"[CalendarService] Parsed {events.Count} total events from ICS.");

                var filtered = FilterRelevantEvents(events);
                if (CalendarReminderOwnership.RustOwnsBackgroundWork)
                    return;
                lock (_eventsLock)
                {
                    _events = filtered;
                }

                _logger.LogInformation($"[CalendarService] Found {filtered.Count} relevant events within the reminder lookback/future window.");
                _monitorService.AddLogMessage($"[Calendar] Refreshed. Found {filtered.Count} events.");
                if (!CalendarReminderOwnership.RustOwnsBackgroundWork)
                    Refreshed?.Invoke(this, EventArgs.Empty);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "[CalendarService] Error during RefreshCalendarAsync");
                _monitorService.AddLogMessage($"[Calendar] Refresh Failed: {ex.Message}");
            }
            finally
            {
                Interlocked.Exchange(ref _isRefreshing, 0);
            }
        }

        public List<CalendarEvent> GetTodayEvents()
        {
            var today = DateTime.Today;
            var tomorrow = today.AddDays(1);
            return GetEventsSnapshot()
                .Where(e => e.Start >= today && e.Start < tomorrow)
                .OrderBy(e => e.Start)
                .ToList();
        }

        public CalendarEvent? GetNextEvent()
        {
            var now = DateTime.Now;
            return GetEventsSnapshot()
                .Where(e => !e.IsAllDay && e.Start > now)
                .OrderBy(e => e.Start)
                .FirstOrDefault();
        }

        public List<CalendarEvent> GetEventsSnapshot()
        {
            lock (_eventsLock)
            {
                return _events.Select(CloneEvent).ToList();
            }
        }

        private List<CalendarEvent> ParseIcs(string data)
        {
            var events = new List<CalendarEvent>();
            var lines = data.Split(new[] { "\r\n", "\n", "\r" }, StringSplitOptions.None);
            
            string? currentSummary = null;
            string? currentStart = null;
            string? currentRRule = null;
            bool isAllDay = false;
            bool inEvent = false;

            foreach (var line in lines)
            {
                if (line.StartsWith("BEGIN:VEVENT"))
                {
                    inEvent = true;
                    currentSummary = null;
                    currentStart = null;
                    currentRRule = null;
                    isAllDay = false;
                }
                else if (line.StartsWith("END:VEVENT"))
                {
                    if (inEvent && currentStart != null)
                    {
                        events.Add(new CalendarEvent
                        {
                            Summary = currentSummary ?? "No Title",
                            Start = ParseIcsDate(currentStart),
                            IsAllDay = isAllDay,
                            RRule = currentRRule
                        });
                    }
                    inEvent = false;
                }
                else if (inEvent)
                {
                    if (line.StartsWith("DTSTART"))
                    {
                        var parts = line.Split(':');
                        if (parts.Length > 1)
                        {
                            currentStart = parts[1];
                            if (line.Contains("VALUE=DATE")) isAllDay = true;
                        }
                    }
                    else if (line.StartsWith("RRULE:"))
                    {
                        currentRRule = line.Substring(6);
                    }
                    else if (line.StartsWith("SUMMARY"))
                    {
                        var parts = line.Split(new[] { ':' }, 2);
                        if (parts.Length > 1) currentSummary = parts[1];
                    }
                }
            }
            return events;
        }

        private DateTime ParseIcsDate(string icalStr)
        {
            // Format: 20260202T120000Z or 20260202
            if (icalStr.Length == 8) // All day: YYYYMMDD
            {
                int y = int.Parse(icalStr.Substring(0, 4));
                int m = int.Parse(icalStr.Substring(4, 2));
                int d = int.Parse(icalStr.Substring(6, 2));
                return new DateTime(y, m, d);
            }

            string clean = icalStr.Replace("Z", "");
            if (clean.Contains("T"))
            {
                var parts = clean.Split('T');
                int y = int.Parse(parts[0].Substring(0, 4));
                int mo = int.Parse(parts[0].Substring(4, 2));
                int d = int.Parse(parts[0].Substring(6, 2));
                int h = int.Parse(parts[1].Substring(0, 2));
                int mi = int.Parse(parts[1].Substring(2, 2));
                int s = int.Parse(parts[1].Substring(4, 2));
                
                // ICS is UTC, convert to Local
                var utcDate = new DateTime(y, mo, d, h, mi, s, DateTimeKind.Utc);
                return utcDate.ToLocalTime();
            }

            return DateTime.MinValue;
        }

        private List<CalendarEvent> FilterRelevantEvents(List<CalendarEvent> events)
        {
            var now = DateTime.Now;
            var today = DateTime.Today;
            var lookbackCutoff = now - ReminderLookback;
            var recurrenceStartDate = lookbackCutoff.Date;
            
            var results = new List<CalendarEvent>();

            foreach(var e in events)
            {
                // 1. Non-recurring: retain recent history as well as the future.
                // The reminder sync layer deduplicates by calendar UID/occurrence identity.
                if (string.IsNullOrEmpty(e.RRule))
                {
                    var isWithinLookback = e.IsAllDay
                        ? e.Start.Date >= recurrenceStartDate
                        : e.Start >= lookbackCutoff;
                    var isOngoing = e.End.HasValue &&
                        (e.IsAllDay
                            ? e.Start.Date <= today && e.End.Value.Date > today
                            : e.Start <= now && e.End.Value > now);

                    if (isWithinLookback || isOngoing)
                    {
                        results.Add(e);
                    }
                    continue;
                }

                // 2. Recurring (RRULE) - Expand far enough for reminder scheduling.
                DateTime? recurrenceUntil = null;
                if (e.RRule.Contains("UNTIL="))
                {
                    var untilPart = e.RRule.Split(';').FirstOrDefault(p => p.StartsWith("UNTIL="));
                    if (untilPart != null)
                    {
                        var untilDate = ParseIcsDate(untilPart.Substring(6));
                        if (untilDate != DateTime.MinValue)
                        {
                            recurrenceUntil = untilDate;
                            if (untilDate < lookbackCutoff)
                            {
                                continue; // Series ended before the recovery window.
                            }
                        }
                    }
                }

                // Simplified recurrence expansion from the reminder lookback through the next year.
                var recurrenceDays = (today.AddDays(366) - recurrenceStartDate).Days;
                for (int i = 0; i < recurrenceDays; i++)
                {
                    var day = recurrenceStartDate.AddDays(i);
                    if (recurrenceUntil.HasValue && day.Date > recurrenceUntil.Value.Date)
                    {
                        continue;
                    }

                    bool match = false;

                    if (e.RRule.Contains("FREQ=DAILY"))
                    {
                        if (e.Start.Date <= day) match = true;
                    }
                    else if (e.RRule.Contains("FREQ=WEEKLY"))
                    {
                        if (e.Start.Date <= day && e.Start.DayOfWeek == day.DayOfWeek) match = true;
                    }
                    else if (e.RRule.Contains("FREQ=YEARLY"))
                    {
                        if (e.Start.Month == day.Month && e.Start.Day == day.Day) match = true;
                    }

                    if (match)
                    {
                        var occurrenceStart = new DateTime(
                            day.Year, day.Month, day.Day,
                            e.Start.Hour, e.Start.Minute, e.Start.Second,
                            DateTimeKind.Local);
                        var occurrenceEnd = e.End.HasValue
                            ? occurrenceStart + (e.End.Value - e.Start)
                            : (DateTime?)null;

                        results.Add(new CalendarEvent
                        {
                            Summary = e.Summary,
                            IsAllDay = e.IsAllDay,
                            Start = occurrenceStart,
                            End = occurrenceEnd,
                            RRule = e.RRule,
                            Uid = e.Uid,
                            Sequence = e.Sequence,
                            LastModified = e.LastModified,
                            Status = e.Status,
                            Description = e.Description,
                            Location = e.Location,
                            RecurrenceId = e.RecurrenceId
                        });
                    }
                }
            }

            // Preserve recent history, but never discard an event that is still in progress.
            return results.Where(e =>
                          (e.End.HasValue &&
                           (e.IsAllDay
                               ? e.Start.Date <= today && e.End.Value.Date > today
                               : e.Start <= now && e.End.Value > now)) ||
                          (e.IsAllDay
                              ? e.Start.Date >= recurrenceStartDate
                              : e.Start >= lookbackCutoff))
                          .OrderBy(e => e.Start)
                          .GroupBy(e => new
                          {
                              Identity = string.IsNullOrWhiteSpace(e.Uid) ? e.Summary : e.Uid,
                              e.Start
                          })
                          .Select(g => g.First())
                          .ToList();
        }

        private CalendarEvent CloneForToday(CalendarEvent e, DateTime today)
        {
            return new CalendarEvent
            {
                Summary = e.Summary,
                IsAllDay = e.IsAllDay,
                Start = new DateTime(today.Year, today.Month, today.Day, e.Start.Hour, e.Start.Minute, e.Start.Second),
                End = e.End.HasValue
                    ? new DateTime(today.Year, today.Month, today.Day, e.Start.Hour, e.Start.Minute, e.Start.Second) + (e.End.Value - e.Start)
                    : null,
                RRule = e.RRule
            };
        }

        private static CalendarEvent CloneEvent(CalendarEvent e)
        {
            return new CalendarEvent
            {
                Summary = e.Summary,
                Start = e.Start,
                End = e.End,
                IsAllDay = e.IsAllDay,
                RRule = e.RRule,
                Uid = e.Uid,
                Sequence = e.Sequence,
                LastModified = e.LastModified,
                Status = e.Status,
                Description = e.Description,
                Location = e.Location,
                RecurrenceId = e.RecurrenceId
            };
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            _timer?.Change(Timeout.Infinite, 0);
            return Task.CompletedTask;
        }

        public void Dispose()
        {
            _timer?.Dispose();
        }
    }
}
