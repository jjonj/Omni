using System.Globalization;
using System.Text.RegularExpressions;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed record ParsedExecutionModifiers(
    string CommandText,
    DateTimeOffset? RunAt,
    string? ModifierText,
    int? SourceStart = null,
    int? SourceLength = null);

public static partial class ExecutionModifierParser
{
    private static readonly IReadOnlyDictionary<string, int> SmallNumbers =
        new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase)
        {
            ["zero"] = 0, ["one"] = 1, ["two"] = 2, ["three"] = 3, ["four"] = 4,
            ["five"] = 5, ["six"] = 6, ["seven"] = 7, ["eight"] = 8, ["nine"] = 9,
            ["ten"] = 10, ["eleven"] = 11, ["twelve"] = 12, ["thirteen"] = 13,
            ["fourteen"] = 14, ["fifteen"] = 15, ["sixteen"] = 16, ["seventeen"] = 17,
            ["eighteen"] = 18, ["nineteen"] = 19, ["twenty"] = 20, ["thirty"] = 30,
            ["forty"] = 40, ["fifty"] = 50
        };

    private const string Amount =
        @"(?:a|an|\d+(?:\.\d+)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty(?:[-\s](?:one|two|three|four|five|six|seven|eight|nine))?|thirty(?:[-\s](?:one|two|three|four|five|six|seven|eight|nine))?|forty(?:[-\s](?:one|two|three|four|five|six|seven|eight|nine))?|fifty(?:[-\s](?:one|two|three|four|five|six|seven|eight|nine))?)";

    private const string Unit =
        @"(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)";

    [GeneratedRegex(
        @"\s+in\s+(?<hours>" + Amount + @")\s+hours?\s+and\s+(?:a\s+)?half(?:\s+hour)?\s*[.!?]?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex HourAndHalfSuffix();

    [GeneratedRegex(
        @"\s+in\s+(?<first>" + Amount + @")\s*(?<firstUnit>" + Unit + @")\s+and\s+(?<second>" + Amount + @")\s*(?<secondUnit>" + Unit + @")\s*[.!?]?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex CompoundSuffix();

    [GeneratedRegex(
        @"\s+in\s+half\s+(?:an?\s+)?hour\s*[.!?]?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex HalfHourSuffix();

    [GeneratedRegex(
        @"\s+in\s+(?<amount>" + Amount + @")\s*(?<unit>" + Unit + @")\s*[.!?]?\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex RelativeSuffix();

    public static ParsedExecutionModifiers Parse(
        string text,
        DateTimeOffset? now = null)
    {
        var trimmed = text.Trim();
        var baseTime = now ?? DateTimeOffset.Now;

        var hourAndHalf = HourAndHalfSuffix().Match(trimmed);
        if (hourAndHalf.Success)
        {
            var hours = ParseAmount(hourAndHalf.Groups["hours"].Value);
            return Build(trimmed, hourAndHalf, baseTime.AddHours(hours + 0.5));
        }

        var compound = CompoundSuffix().Match(trimmed);
        if (compound.Success)
        {
            var delay =
                ToDelay(ParseAmount(compound.Groups["first"].Value), compound.Groups["firstUnit"].Value) +
                ToDelay(ParseAmount(compound.Groups["second"].Value), compound.Groups["secondUnit"].Value);
            return Build(trimmed, compound, baseTime.Add(delay));
        }

        var half = HalfHourSuffix().Match(trimmed);
        if (half.Success)
            return Build(trimmed, half, baseTime.AddMinutes(30));

        var single = RelativeSuffix().Match(trimmed);
        if (!single.Success)
            return new ParsedExecutionModifiers(trimmed, null, null);

        var singleDelay = ToDelay(
            ParseAmount(single.Groups["amount"].Value),
            single.Groups["unit"].Value);
        return Build(trimmed, single, baseTime.Add(singleDelay));
    }

    private static ParsedExecutionModifiers Build(
        string text,
        Match match,
        DateTimeOffset runAt)
        => new(
            text[..match.Index].Trim(),
            runAt,
            match.Value.Trim(),
            match.Index,
            match.Length);

    private static double ParseAmount(string value)
    {
        var normalized = value.Trim().ToLowerInvariant();
        if (normalized is "a" or "an")
            return 1;

        if (double.TryParse(normalized, NumberStyles.Float, CultureInfo.InvariantCulture, out var numeric))
            return numeric;

        var parts = normalized.Split(new[] { ' ', '-' }, StringSplitOptions.RemoveEmptyEntries);
        var total = 0;
        foreach (var part in parts)
        {
            if (!SmallNumbers.TryGetValue(part, out var number))
                throw new FormatException($"Unsupported spoken number '{value}'.");
            total += number;
        }
        return total;
    }

    private static TimeSpan ToDelay(double amount, string unit)
    {
        unit = unit.ToLowerInvariant();
        if (unit.StartsWith("sec", StringComparison.Ordinal))
            return TimeSpan.FromSeconds(amount);
        if (unit.StartsWith("min", StringComparison.Ordinal))
            return TimeSpan.FromMinutes(amount);
        if (unit.StartsWith("hour", StringComparison.Ordinal) || unit.StartsWith("hr", StringComparison.Ordinal))
            return TimeSpan.FromHours(amount);
        return TimeSpan.FromDays(amount);
    }
}
