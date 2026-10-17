using System.Text.Json;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public enum AssistantSourceKind
{
    AndroidVoice,
    Keep,
    Api,
    PcVoice,
    Resolver
}

public sealed record AssistantSource(
    AssistantSourceKind Kind,
    string? DeviceId,
    AssistantTrustLevel Trust);

public sealed record ExecutionModifiers(
    DateTimeOffset? RunAt,
    string? ModifierText,
    int? SourceStart = null,
    int? SourceLength = null);

public sealed record AssistantRequest(
    Guid RequestId,
    string Text,
    AssistantSource Source,
    string? SourceItemId,
    DateTimeOffset OccurredAt,
    DateTimeOffset ReceivedAt,
    string? ConversationId = null,
    bool DryRun = false);

public enum RunState
{
    Accepted,
    Resolved,
    Scheduled,
    Running,
    Succeeded,
    Failed,
    Skipped,
    NeedsClarification,
    Unhandled,
    Interrupted,
    Cancelled
}

public sealed record CommandRun(
    Guid RunId,
    Guid RequestId,
    string? CommandId,
    int? CommandVersion,
    Dictionary<string, JsonElement>? Arguments,
    ExecutionModifiers Modifiers,
    string HostId,
    DateTimeOffset IntendedAt,
    RunState State,
    string? Reason,
    string? Summary,
    DateTimeOffset UpdatedAt,
    AssistantSource Source,
    string? SourceItemId,
    string OriginalText,
    DateTimeOffset OccurredAt,
    DateTimeOffset ReceivedAt,
    string? ConversationId,
    bool DryRun);

public sealed record AssistantSubmission(
    Guid RunId,
    RunState State,
    string? CommandId,
    string Decision,
    bool IsDuplicate = false);

public abstract record AssistantRouteResult
{
    public sealed record Matched(AssistantCommandMatch Match) : AssistantRouteResult;
    public sealed record Ambiguous(IReadOnlyList<AssistantCommandMatch> Candidates) : AssistantRouteResult;
    public sealed record None : AssistantRouteResult;
}
