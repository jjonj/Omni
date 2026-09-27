using System.Text.RegularExpressions;
using OmniSync.Hub.Models;

namespace OmniSync.Hub.Logic.Services;

public sealed record ParsedReminderTitle(ReminderKind Kind, string Title);

public static partial class ReminderTitleParser
{
    private const string EmptyTitleFallback = "Untitled reminder";

    [GeneratedRegex(
        @"^\s*omni(?:\s*[:\-])?\s+(?:(alarm|reminder|task)(?:\s*[:\-])?\s+)?(.+?)\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex OmniTitleRegex();

    public static bool TryParse(string? calendarTitle, out ParsedReminderTitle? parsed)
    {
        parsed = null;
        if (string.IsNullOrWhiteSpace(calendarTitle))
            return false;

        var match = OmniTitleRegex().Match(calendarTitle);
        if (!match.Success)
            return false;

        var kind = match.Groups[1].Value.ToLowerInvariant() switch
        {
            "alarm" => ReminderKind.Alarm,
            "reminder" => ReminderKind.Reminder,
            "task" => ReminderKind.Task,
            _ => ReminderKind.Task
        };

        var title = match.Groups[2].Value.Trim();
        if (string.IsNullOrWhiteSpace(title))
            title = EmptyTitleFallback;

        parsed = new ParsedReminderTitle(kind, title);
        return true;
    }
}
