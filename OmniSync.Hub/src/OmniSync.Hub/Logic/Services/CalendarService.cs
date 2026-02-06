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
    public class CalendarEvent
    {
        public string Summary { get; set; } = "No Title";
        public DateTime Start { get; set; }
        public bool IsAllDay { get; set; }
        public string? RRule { get; set; }
    }

    public class CalendarService : IHostedService, IDisposable
    {
        private readonly HttpClient _httpClient;
        private readonly HubMonitorService _monitorService;
        private readonly HubSettingsService _settingsService;
        private readonly ILogger<CalendarService> _logger;
        private System.Threading.Timer? _timer;
        private List<CalendarEvent> _events = new();

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

            _settingsService.SettingsChanged += (s, e) =>
            {
                _ = RefreshCalendarAsync();
            };
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation("CalendarService starting.");
            _timer = new System.Threading.Timer(DoWork, null, TimeSpan.Zero, TimeSpan.FromMinutes(15));
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

                var events = ParseIcs(icsData);
                _logger.LogInformation($"[CalendarService] Parsed {events.Count} total events from ICS.");

                _events = FilterToday(events);
                _logger.LogInformation($"[CalendarService] Found {_events.Count} valid events for today/future.");

                _monitorService.AddLogMessage($"[Calendar] Refreshed. Found {_events.Count} events.");
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "[CalendarService] Error during RefreshCalendarAsync");
                _monitorService.AddLogMessage($"[Calendar] Refresh Failed: {ex.Message}");
            }
        }

        public List<CalendarEvent> GetTodayEvents() 
        {
            var today = DateTime.Today;
            var tomorrow = today.AddDays(1);
            return _events.Where(e => e.Start >= today && e.Start < tomorrow).OrderBy(e => e.Start).ToList();
        }

        public CalendarEvent? GetNextEvent()
        {
            var now = DateTime.Now;
            return _events
                .Where(e => !e.IsAllDay && e.Start > now)
                .OrderBy(e => e.Start)
                .FirstOrDefault();
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

        private List<CalendarEvent> FilterToday(List<CalendarEvent> events)
        {
            var now = DateTime.Now;
            var today = DateTime.Today;
            
            var results = new List<CalendarEvent>();

            foreach(var e in events)
            {
                // 1. Non-recurring: Include if it's in the future or today
                if (string.IsNullOrEmpty(e.RRule))
                {
                    if (e.Start >= today || (e.IsAllDay && e.Start.Date == today))
                    {
                        results.Add(e);
                    }
                    continue;
                }

                // 2. Recurring (RRULE) - Expand for the next 14 days
                if (e.RRule.Contains("UNTIL="))
                {
                    var untilPart = e.RRule.Split(';').FirstOrDefault(p => p.StartsWith("UNTIL="));
                    if (untilPart != null)
                    {
                        var untilDate = ParseIcsDate(untilPart.Substring(6));
                        if (untilDate != DateTime.MinValue && untilDate < today)
                        {
                            continue; // Recurrence ended in the past
                        }
                    }
                }

                // Simplified recurrence expansion for 14 days
                for (int i = 0; i < 14; i++)
                {
                    var day = today.AddDays(i);
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
                        results.Add(new CalendarEvent
                        {
                            Summary = e.Summary,
                            IsAllDay = e.IsAllDay,
                            Start = new DateTime(day.Year, day.Month, day.Day, e.Start.Hour, e.Start.Minute, e.Start.Second),
                            RRule = e.RRule
                        });
                    }
                }
            }

            // Final filter: only return events that haven't ended yet
            return results.Where(e => e.IsAllDay || e.Start > now.AddMinutes(-5))
                          .OrderBy(e => e.Start)
                          .GroupBy(e => new { e.Summary, e.Start }) // Deduplicate instances
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
                RRule = e.RRule
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
