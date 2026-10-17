using OmniSync.Hub.Logic.Services.VoiceInbox;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class DeterministicCommandVoiceHandler : IVoiceTextHandler
{
    private readonly AssistantRequestService _requests;

    public DeterministicCommandVoiceHandler(AssistantRequestService requests)
    {
        _requests = requests;
    }

    public string Name => "Command";

    public async Task<VoiceHandlerResult?> TryHandleAsync(
        VoiceInboxItem item,
        CancellationToken cancellationToken)
    {
        var source = ToSource(item);
        var request = new AssistantRequest(
            item.RequestId ?? Guid.NewGuid(),
            item.Text,
            source,
            item.SourceId,
            item.OccurredAt ?? item.ReceivedAt,
            item.ReceivedAt,
            item.ConversationId,
            item.DryRun);

        var submission = await _requests.SubmitAsync(request, cancellationToken);
        if (submission.State == RunState.Unhandled)
            return null;

        var summary = submission.State switch
        {
            RunState.Scheduled => $"Scheduled {submission.CommandId}.",
            RunState.Accepted => $"Accepted {submission.CommandId}.",
            RunState.Resolved => $"Resolved {submission.CommandId} ({submission.Decision}).",
            RunState.Skipped => submission.Decision,
            RunState.NeedsClarification => submission.Decision,
            RunState.Failed => submission.Decision,
            _ => submission.Decision
        };

        return new VoiceHandlerResult(
            summary,
            submission.RunId,
            submission.State.ToString(),
            submission.CommandId,
            submission.Decision);
    }

    private static AssistantSource ToSource(VoiceInboxItem item)
    {
        var normalized = item.Source.Trim().ToLowerInvariant();
        return normalized switch
        {
            "android-voice" => new(
                AssistantSourceKind.AndroidVoice,
                item.SourceId,
                AssistantTrustLevel.Trusted),
            "google-keep" => new(
                AssistantSourceKind.Keep,
                null,
                AssistantTrustLevel.Normal),
            "pc-voice" => new(
                AssistantSourceKind.PcVoice,
                item.SourceId,
                AssistantTrustLevel.Trusted),
            "resolver" => new(
                AssistantSourceKind.Resolver,
                item.SourceId,
                AssistantTrustLevel.Trusted),
            _ => new(
                AssistantSourceKind.Api,
                item.SourceId,
                AssistantTrustLevel.Normal)
        };
    }
}
