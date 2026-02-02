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
        private const string ICS_URL = "https://calendar.google.com/calendar/ical/jjonjex%40gmail.com/public/basic.ics";
        private readonly HttpClient _httpClient;
        private readonly HubMonitorService _monitorService;
        private readonly ILogger<CalendarService> _logger;
        private System.Threading.Timer? _timer;
        private List<CalendarEvent> _events = new();

        public CalendarService(
            HttpClient httpClient,
            HubMonitorService monitorService,
            ILogger<CalendarService> logger)
        {
            _httpClient = httpClient;
            _monitorService = monitorService;
            _logger = logger;
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

                        _logger.LogInformation($"[CalendarService] Refreshing from: {ICS_URL}");

                        var icsData = await _httpClient.GetStringAsync(ICS_URL);

                        _logger.LogInformation($"[CalendarService] Received {icsData.Length} bytes of ICS data.");
                _logger.LogInformation($"[CalendarService] Raw ICS Prefix: {new string(icsData.Take(500).ToArray())}");

                        

                        var events = ParseIcs(icsData);

                        _logger.LogInformation($"[CalendarService] Parsed {events.Count} total events from ICS.");

                        

                        _events = FilterToday(events);

                        _logger.LogInformation($"[CalendarService] Found {_events.Count} events for today.");

                        

                        foreach(var e in _events)

                        {

                            _logger.LogInformation($"[CalendarService] Today's Event: {e.Summary} at {e.Start}");

                        }

        

                        _monitorService.AddLogMessage($"[Calendar] Refreshed. Found {_events.Count} events for today.");

                    }

                    catch (Exception ex)

                    {

                        _logger.LogError(ex, "[CalendarService] Error during RefreshCalendarAsync");

                        _monitorService.AddLogMessage($"[Calendar] Refresh Failed: {ex.Message}");

                    }

                }

        public List<CalendarEvent> GetTodayEvents() => _events.OrderBy(e => e.Start).ToList();

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
            var today = DateTime.Today;
            var endOfToday = today.AddDays(1).AddSeconds(-1);
            
            var results = new List<CalendarEvent>();

            foreach(var e in events)
            {
                // 1. Direct match for today
                if (e.Start >= today && e.Start <= endOfToday)
                {
                    results.Add(e);
                    continue;
                }

                // 2. Basic Recurring (RRULE)
                if (!string.IsNullOrEmpty(e.RRule))
                {
                    if (e.RRule.Contains("FREQ=YEARLY"))
                    {
                        if (e.Start.Month == today.Month && e.Start.Day == today.Day)
                        {
                            var cloned = CloneForToday(e, today);
                            results.Add(cloned);
                        }
                    }
                    else if (e.RRule.Contains("FREQ=DAILY"))
                    {
                        if (e.Start < today)
                        {
                            results.Add(CloneForToday(e, today));
                        }
                    }
                    else if (e.RRule.Contains("FREQ=WEEKLY"))
                    {
                        if (e.Start < today && e.Start.DayOfWeek == today.DayOfWeek)
                        {
                            results.Add(CloneForToday(e, today));
                        }
                    }
                }
            }

            return results.OrderBy(e => e.Start).ToList();
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
