using System.Globalization;

namespace OmniSync.Hub.Logic.Services;

public static class CalendarIcsParser
{
    public static List<CalendarEvent> Parse(string data)
    {
        ArgumentNullException.ThrowIfNull(data);

        var events = new List<CalendarEvent>();
        var lines = UnfoldLines(data);
        CalendarEvent? current = null;
        var hasStart = false;

        foreach (var line in lines)
        {
            if (line.Equals("BEGIN:VEVENT", StringComparison.OrdinalIgnoreCase))
            {
                current = new CalendarEvent();
                hasStart = false;
                continue;
            }

            if (line.Equals("END:VEVENT", StringComparison.OrdinalIgnoreCase))
            {
                if (current is not null && hasStart)
                    events.Add(current);
                current = null;
                continue;
            }

            if (current is null)
                continue;

            if (!TrySplitProperty(line, out var name, out var parameters, out var value))
                continue;

            switch (name)
            {
                case "DTSTART":
                    if (TryParseDate(value, parameters, out var start, out var isAllDay))
                    {
                        current.Start = start;
                        current.IsAllDay = isAllDay;
                        hasStart = true;
                    }
                    break;
                case "DTEND":
                    if (TryParseDate(value, parameters, out var end, out _))
                    {
                        current.End = end;
                    }
                    break;
                case "SUMMARY":
                    current.Summary = DecodeText(value);
                    break;
                case "UID":
                    current.Uid = value.Trim();
                    break;
                case "RRULE":
                    current.RRule = value.Trim();
                    break;
                case "SEQUENCE":
                    current.Sequence = int.TryParse(value, out var sequence) ? sequence : 0;
                    break;
                case "LAST-MODIFIED":
                    current.LastModified = value.Trim();
                    break;
                case "STATUS":
                    current.Status = value.Trim();
                    break;
                case "DESCRIPTION":
                    current.Description = DecodeText(value);
                    break;
                case "LOCATION":
                    current.Location = DecodeText(value);
                    break;
                case "RECURRENCE-ID":
                    current.RecurrenceId = value.Trim();
                    break;
            }
        }

        return events;
    }

    private static List<string> UnfoldLines(string data)
    {
        var rawLines = data.Split(
            new[] { "\r\n", "\n", "\r" },
            StringSplitOptions.None);

        var lines = new List<string>(rawLines.Length);
        foreach (var raw in rawLines)
        {
            if ((raw.StartsWith(' ') || raw.StartsWith('\t')) && lines.Count > 0)
            {
                lines[^1] += raw.Length > 0 ? raw[1..] : string.Empty;
            }
            else
            {
                lines.Add(raw);
            }
        }

        return lines;
    }

    private static bool TrySplitProperty(
        string line,
        out string name,
        out Dictionary<string, string> parameters,
        out string value)
    {
        name = string.Empty;
        value = string.Empty;
        parameters = new(StringComparer.OrdinalIgnoreCase);

        var colon = line.IndexOf(':');
        if (colon <= 0)
            return false;

        var left = line[..colon];
        value = line[(colon + 1)..];

        var parts = left.Split(';');
        name = parts[0].Trim().ToUpperInvariant();
        for (var i = 1; i < parts.Length; i++)
        {
            var equals = parts[i].IndexOf('=');
            if (equals <= 0)
                continue;

            var key = parts[i][..equals].Trim();
            var parameterValue = parts[i][(equals + 1)..].Trim().Trim('"');
            parameters[key] = parameterValue;
        }

        return name.Length > 0;
    }

    private static bool TryParseDate(
        string value,
        IReadOnlyDictionary<string, string> parameters,
        out DateTime result,
        out bool isAllDay)
    {
        result = DateTime.MinValue;
        isAllDay =
            parameters.TryGetValue("VALUE", out var valueType) &&
            valueType.Equals("DATE", StringComparison.OrdinalIgnoreCase);

        if (isAllDay || value.Length == 8)
        {
            isAllDay = true;
            if (!DateTime.TryParseExact(
                    value,
                    "yyyyMMdd",
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.None,
                    out var date))
                return false;

            result = DateTime.SpecifyKind(date, DateTimeKind.Local);
            return true;
        }

        var utc = value.EndsWith('Z');
        var raw = utc ? value[..^1] : value;
        var formats = new[] { "yyyyMMdd'T'HHmmss", "yyyyMMdd'T'HHmm" };

        if (!DateTime.TryParseExact(
                raw,
                formats,
                CultureInfo.InvariantCulture,
                DateTimeStyles.None,
                out var parsed))
            return false;

        if (utc)
        {
            result = DateTime.SpecifyKind(parsed, DateTimeKind.Utc).ToLocalTime();
            return true;
        }

        if (parameters.TryGetValue("TZID", out var timezoneId) &&
            TryFindTimezone(timezoneId, out var timezone))
        {
            var unspecified = DateTime.SpecifyKind(parsed, DateTimeKind.Unspecified);
            result = TimeZoneInfo.ConvertTimeToUtc(unspecified, timezone).ToLocalTime();
            return true;
        }

        result = DateTime.SpecifyKind(parsed, DateTimeKind.Local);
        return true;
    }

    private static bool TryFindTimezone(string timezoneId, out TimeZoneInfo timezone)
    {
        try
        {
            timezone = TimeZoneInfo.FindSystemTimeZoneById(timezoneId);
            return true;
        }
        catch (TimeZoneNotFoundException)
        {
        }
        catch (InvalidTimeZoneException)
        {
        }

        timezone = TimeZoneInfo.Local;
        return false;
    }

    private static string DecodeText(string value)
    {
        return value
            .Replace("\\n", "\n", StringComparison.OrdinalIgnoreCase)
            .Replace("\\,", ",")
            .Replace("\\;", ";")
            .Replace("\\\\", "\\")
            .Trim();
    }
}
