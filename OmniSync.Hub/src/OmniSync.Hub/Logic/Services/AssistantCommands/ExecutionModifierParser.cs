using System.Text.RegularExpressions;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed record ParsedExecutionModifiers(
    string CommandText,
    DateTimeOffset? RunAt,
    string? ModifierText);

public static partial class ExecutionModifierParser
{
    private static readonly IReadOnlyDictionary<string, double> WordNumbers =
        new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase)
        {
            ["one"] = 1,
            ["two"] = 2,
            ["three"] = 3,
            ["four"] = 4,
            ["five"] = 5,
            ["six"] = 6,
            ["seven"] = 7,
            ["eight"] = 8,
            ["nine"] = 9,
            ["ten"] = 10
        };

    [GeneratedRegex(
        @"\s+\bin\s+(?<amount>\d+(?:\.\d+)?|one|two|three|four|five|six|seven|eight|nine|ten)\s*(?<unit>seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\s*[.!?]?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex RelativeSuffix();

    [GeneratedRegex(
        @"\s+\bin\s+half\s+(?:an?\s+)?hour\s*[.!?]?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex HalfHourSuffix();

    public static ParsedExecutionModifiers Parse(
        string text,
        DateTimeOffset? now = null)
    {
        var trimmed = text.Trim();
        var baseTime = now ?? DateTimeOffset.Now;

        var halfMatch = HalfHourSuffix().Match(trimmed);
        if (halfMatch.Success)
        {
            return new ParsedExecutionModifiers(
                trimmed[..halfMatch.Index].Trim(),
                baseTime.AddMinutes(30),
                halfMatch.Value.Trim());
        }

        var match = RelativeSuffix().Match(trimmed);
        if (!match.Success)
            return new ParsedExecutionModifiers(trimmed, null, null);

        var amountText = match.Groups["amount"].Value;
        var amount = WordNumbers.TryGetValue(amountText, out var wordValue)
            ? wordValue
            : double.Parse(amountText, System.Globalization.CultureInfo.InvariantCulture);

        var unit = match.Groups["unit"].Value.ToLowerInvariant();
        var delay = unit.StartsWith("sec", StringComparison.Ordinal)
            ? TimeSpan.FromSeconds(amount)
            : unit.StartsWith("min", StringComparison.Ordinal)
                ? TimeSpan.FromMinutes(amount)
                : unit.StartsWith("hour", StringComparison.Ordinal) || unit.StartsWith("hr", StringComparison.Ordinal)
                    ? TimeSpan.FromHours(amount)
                    : TimeSpan.FromDays(amount);

        return new ParsedExecutionModifiers(
            trimmed[..match.Index].Trim(),
            baseTime.Add(delay),
            match.Value.Trim());
    }
}
