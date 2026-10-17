using System.Globalization;
using System.Text.Json;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public enum AssistantExecutionHost
{
    Desktop,
    Android,
    Either
}

public enum AssistantArgumentType
{
    String,
    Integer,
    Boolean
}

[Flags]
public enum AssistantTimingSupport
{
    None = 0,
    RelativeTime = 1,
    AbsoluteTime = 2
}

public enum AssistantRiskLevel
{
    Low,
    Medium,
    High
}

public enum AssistantTrustLevel
{
    Low,
    Normal,
    Trusted
}

public sealed record AssistantArgumentSpec(
    string Name,
    AssistantArgumentType Type,
    bool Required,
    string Description);

public sealed record AssistantCommandExample(
    string Utterance,
    IReadOnlyDictionary<string, string> Arguments);

public sealed record AssistantStalenessPolicy(
    TimeSpan? MaxDelay,
    bool RunAfterHostRestart = true)
{
    public static AssistantStalenessPolicy Unlimited { get; } = new((TimeSpan?)null);
}

public sealed record AssistantRiskPolicy(
    AssistantRiskLevel Level,
    AssistantTrustLevel MinTrustWithoutConfirmation = AssistantTrustLevel.Low);

public sealed record AssistantExecutionPolicy(
    TimeSpan Timeout,
    string? ConcurrencyKey,
    bool Idempotent,
    IReadOnlyList<string>? RequiredFiles = null);

public sealed record AssistantCommandDescriptor(
    string Id,
    int Version,
    string Description,
    AssistantExecutionHost Host,
    IReadOnlyList<AssistantArgumentSpec> Arguments,
    IReadOnlyList<AssistantCommandExample> Examples,
    AssistantTimingSupport Timing,
    AssistantStalenessPolicy Staleness,
    AssistantRiskPolicy Risk,
    AssistantExecutionPolicy Execution);

public sealed record AssistantParseInput(
    string Text,
    CultureInfo? Culture = null);

public sealed record AssistantParseResult(
    IReadOnlyDictionary<string, JsonElement> Arguments,
    int Specificity);

public sealed record AssistantCommandMatch(
    IAssistantCommand Command,
    IReadOnlyDictionary<string, JsonElement> Arguments,
    int Specificity);

public sealed record AssistantChoice(
    string Label,
    IReadOnlyDictionary<string, JsonElement>? Arguments = null);

public abstract record AssistantCommandOutcome
{
    public sealed record Succeeded(
        string Summary,
        JsonElement? Evidence = null) : AssistantCommandOutcome;

    public sealed record Failed(
        string Summary,
        bool Retryable = false) : AssistantCommandOutcome;

    public sealed record NeedsClarification(
        string Question,
        IReadOnlyList<AssistantChoice> Choices) : AssistantCommandOutcome;
}

public sealed record AssistantCommandInvocation(
    CommandRun Run,
    AssistantRequest Request);

public interface IAssistantCommand
{
    AssistantCommandDescriptor Descriptor { get; }

    AssistantParseResult? TryParse(AssistantParseInput input);

    Task<AssistantCommandOutcome> ExecuteAsync(
        AssistantCommandInvocation invocation,
        CancellationToken cancellationToken);
}

internal static class AssistantJson
{
    public static JsonElement String(string value)
        => JsonSerializer.SerializeToElement(value);

    public static string GetString(
        IReadOnlyDictionary<string, JsonElement> arguments,
        string name)
        => arguments.TryGetValue(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;
}
