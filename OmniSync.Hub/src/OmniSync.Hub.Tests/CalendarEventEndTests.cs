using System.Reflection;
using System.Runtime.CompilerServices;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Presentation;

public class CalendarEventEndTests
{
    [Fact]
    public void ParserReadsDtEnd()
    {
        const string ics = """
BEGIN:VCALENDAR
BEGIN:VEVENT
UID:test-event
DTSTART:20261006T100000
DTEND:20261006T113000
SUMMARY:Test event
END:VEVENT
END:VCALENDAR
""";

        var calendarEvent = Assert.Single(CalendarIcsParser.Parse(ics));

        Assert.NotNull(calendarEvent.End);
        Assert.Equal(TimeSpan.FromMinutes(90), calendarEvent.End!.Value - calendarEvent.Start);
    }

    [Fact]
    public void RecurrenceExpansionPreservesDuration()
    {
        var start = DateTime.Today.AddHours(9);
        var source = new CalendarEvent
        {
            Uid = "daily-test",
            Summary = "Daily test",
            Start = start,
            End = start.AddHours(2),
            RRule = "FREQ=DAILY"
        };

        var service = (CalendarService)RuntimeHelpers.GetUninitializedObject(typeof(CalendarService));
        var method = typeof(CalendarService).GetMethod("FilterRelevantEvents", BindingFlags.Instance | BindingFlags.NonPublic)!;
        var expanded = (List<CalendarEvent>)method.Invoke(service, new object[] { new List<CalendarEvent> { source } })!;

        var today = Assert.Single(expanded, e => e.Start == start);
        Assert.NotNull(today.End);
        Assert.Equal(TimeSpan.FromHours(2), today.End!.Value - today.Start);
    }

    [Fact]
    public void LongRunningAllDayEventIsRetainedAndShowsInclusiveEndDate()
    {
        var source = new CalendarEvent
        {
            Uid = "multi-day-test",
            Summary = "Multi-day test",
            Start = DateTime.Today.AddDays(-6),
            End = DateTime.Today.AddDays(5),
            IsAllDay = true
        };

        var service = (CalendarService)RuntimeHelpers.GetUninitializedObject(typeof(CalendarService));
        var method = typeof(CalendarService).GetMethod("FilterRelevantEvents", BindingFlags.Instance | BindingFlags.NonPublic)!;
        var filtered = (List<CalendarEvent>)method.Invoke(service, new object[] { new List<CalendarEvent> { source } })!;

        Assert.Single(filtered, e => e.Uid == source.Uid);

        var converter = new CalendarEventEndConverter();
        var display = converter.Convert(source, typeof(string), null!, System.Globalization.CultureInfo.InvariantCulture);
        Assert.Equal(source.End.Value.Date.AddDays(-1).ToString("MMM dd"), display);
    }
}
