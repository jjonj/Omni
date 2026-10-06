using OmniSync.Hub.Logic.Services.VoiceInbox;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class DeterministicCommandVoiceHandler : IVoiceTextHandler
{
    private readonly AssistantCommandRegistry _registry;
    private readonly AssistantCommandScheduler _scheduler;

    public DeterministicCommandVoiceHandler(
        AssistantCommandRegistry registry,
        AssistantCommandScheduler scheduler)
    {
        _registry = registry;
        _scheduler = scheduler;
    }

    public string Name => "Command";

    public async Task<VoiceHandlerResult?> TryHandleAsync(
        VoiceInboxItem item,
        CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.Now;
        var modifierBaseTime = item.OccurredAt ?? now;
        var modifiers = ExecutionModifierParser.Parse(item.Text, modifierBaseTime);
        var match = _registry.TryResolve(modifiers.CommandText);
        if (match is null)
            return null;

        var descriptor = match.Command.Descriptor;
        if (descriptor.Host == AssistantExecutionHost.Android)
            return null;

        if (item.IsBacklog &&
            !descriptor.QueuePolicy.RunFromBacklog)
        {
            return new VoiceHandlerResult(
                $"Skipped queued {descriptor.Id}; it is configured not to run from a startup backlog.");
        }

        if (modifiers.RunAt is { } runAt &&
            runAt > now.AddMilliseconds(500))
        {
            _scheduler.Schedule(match, runAt);
            return new VoiceHandlerResult(
                $"Scheduled {descriptor.Id} for {runAt.LocalDateTime:g}.");
        }

        var result = await _registry.ExecuteAsync(
            match,
            cancellationToken);
        return new VoiceHandlerResult(result.Summary);
    }
}
