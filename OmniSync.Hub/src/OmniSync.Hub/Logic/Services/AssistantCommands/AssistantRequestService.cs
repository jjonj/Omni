using System.Text.Json;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class AssistantRequestService
{
    private readonly AssistantCommandCatalog _catalog;
    private readonly AssistantHostSelector _hostSelector;
    private readonly AssistantRunStore _store;
    private readonly AssistantCommandDispatcherService _dispatcher;
    private readonly AssistantRunEventSink _events;
    private readonly TimeProvider _timeProvider;

    public AssistantRequestService(
        AssistantCommandCatalog catalog,
        AssistantHostSelector hostSelector,
        AssistantRunStore store,
        AssistantCommandDispatcherService dispatcher,
        AssistantRunEventSink events,
        TimeProvider timeProvider)
    {
        _catalog = catalog;
        _hostSelector = hostSelector;
        _store = store;
        _dispatcher = dispatcher;
        _events = events;
        _timeProvider = timeProvider;
    }

    public async Task<AssistantSubmission> SubmitAsync(
        AssistantRequest request,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();

        if (_store.FindExisting(request) is { } duplicate)
        {
            return new AssistantSubmission(
                duplicate.RunId,
                duplicate.State,
                duplicate.CommandId,
                duplicate.Reason ?? duplicate.Summary ?? duplicate.State.ToString(),
                IsDuplicate: true);
        }

        var now = _timeProvider.GetUtcNow();
        var (route, modifiers) = Resolve(request);

        if (route is AssistantRouteResult.None)
        {
            var unhandled = CreateRun(
                request,
                command: null,
                arguments: null,
                modifiers,
                request.OccurredAt,
                RunState.Unhandled,
                "No deterministic command matched.",
                now);
            _store.Add(unhandled);
            await _events.PublishAsync(unhandled);
            return new AssistantSubmission(
                unhandled.RunId,
                unhandled.State,
                null,
                unhandled.Reason!);
        }

        if (route is AssistantRouteResult.Ambiguous ambiguous)
        {
            var ids = ambiguous.Candidates
                .Select(candidate => candidate.Command.Descriptor.Id)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToArray();
            var reason = "Ambiguous command: " + string.Join(", ", ids);
            var run = CreateRun(
                request,
                command: null,
                arguments: null,
                modifiers,
                modifiers.RunAt ?? request.OccurredAt,
                RunState.NeedsClarification,
                reason,
                now);
            _store.Add(run);
            await _events.PublishAsync(run);
            return new AssistantSubmission(
                run.RunId,
                run.State,
                null,
                reason);
        }

        var match = ((AssistantRouteResult.Matched)route).Match;
        var descriptor = match.Command.Descriptor;
        var validationError = ValidateArguments(descriptor, match.Arguments);
        if (validationError is not null)
        {
            var invalid = CreateRun(
                request,
                match.Command,
                match.Arguments,
                modifiers,
                modifiers.RunAt ?? request.OccurredAt,
                RunState.Failed,
                validationError,
                now);
            _store.Add(invalid);
            await _events.PublishAsync(invalid);
            return new AssistantSubmission(
                invalid.RunId,
                invalid.State,
                descriptor.Id,
                validationError);
        }

        var intendedAt = modifiers.RunAt ?? request.OccurredAt;
        var maxDelay = descriptor.Staleness.MaxDelay;
        if (maxDelay is not null &&
            now > intendedAt &&
            now - intendedAt > maxDelay.Value)
        {
            var stale = CreateRun(
                request,
                match.Command,
                match.Arguments,
                modifiers,
                intendedAt,
                RunState.Skipped,
                $"Request is stale by {(now - intendedAt):g}.",
                now);
            _store.Add(stale);
            await _events.PublishAsync(stale);
            return new AssistantSubmission(
                stale.RunId,
                stale.State,
                descriptor.Id,
                stale.Reason!);
        }

        if (request.Source.Trust < descriptor.Risk.MinTrustWithoutConfirmation)
        {
            var reason =
                $"{descriptor.Id} requires confirmation from source trust {request.Source.Trust}.";
            var denied = CreateRun(
                request,
                match.Command,
                match.Arguments,
                modifiers,
                intendedAt,
                RunState.NeedsClarification,
                reason,
                now);
            _store.Add(denied);
            await _events.PublishAsync(denied);
            return new AssistantSubmission(
                denied.RunId,
                denied.State,
                descriptor.Id,
                reason);
        }

        var hostId = _hostSelector.SelectHost(match.Command);
        if (hostId is null)
        {
            var reason = $"No execution host is currently available for {descriptor.Id}.";
            var unavailable = CreateRun(
                request,
                match.Command,
                match.Arguments,
                modifiers,
                intendedAt,
                RunState.Failed,
                reason,
                now);
            _store.Add(unavailable);
            await _events.PublishAsync(unavailable);
            return new AssistantSubmission(
                unavailable.RunId,
                unavailable.State,
                descriptor.Id,
                reason);
        }

        var remoteHost = AssistantHostSelector.IsRemote(hostId);
        var state = request.DryRun
            ? RunState.Resolved
            : remoteHost
                ? RunState.Accepted
                : intendedAt > now.AddMilliseconds(500)
                    ? RunState.Scheduled
                    : RunState.Accepted;
        var decision = request.DryRun
            ? "dry-run"
            : remoteHost && intendedAt > now.AddMilliseconds(500)
                ? "accepted for remote scheduling"
                : state == RunState.Scheduled
                    ? "scheduled"
                    : "accepted";

        var accepted = CreateRun(
            request,
            match.Command,
            match.Arguments,
            modifiers,
            intendedAt,
            state,
            decision,
            now,
            hostId);
        _store.Add(accepted);
        await _events.PublishAsync(accepted);

        if (state == RunState.Accepted)
            _dispatcher.Enqueue(accepted.RunId);

        return new AssistantSubmission(
            accepted.RunId,
            accepted.State,
            descriptor.Id,
            decision);
    }

    private (AssistantRouteResult Route, ExecutionModifiers Modifiers) Resolve(
        AssistantRequest request)
    {
        var parsed = ExecutionModifierParser.Parse(request.Text, request.OccurredAt);
        var noModifiers = new ExecutionModifiers(null, null);

        if (parsed.RunAt is null)
            return (_catalog.Resolve(request.Text), noModifiers);

        var stripped = _catalog.Resolve(
            parsed.CommandText,
            command => (command.Descriptor.Timing & AssistantTimingSupport.RelativeTime) != 0);
        var full = _catalog.Resolve(request.Text);

        if (full is AssistantRouteResult.Matched fullMatch)
        {
            if (stripped is not AssistantRouteResult.Matched strippedMatch ||
                fullMatch.Match.Specificity >= strippedMatch.Match.Specificity)
            {
                return (full, noModifiers);
            }
        }

        if (stripped is not AssistantRouteResult.None)
        {
            return (
                stripped,
                new ExecutionModifiers(
                    parsed.RunAt,
                    parsed.ModifierText,
                    parsed.SourceStart,
                    parsed.SourceLength));
        }

        return (full, noModifiers);
    }

    private static string? ValidateArguments(
        AssistantCommandDescriptor descriptor,
        IReadOnlyDictionary<string, JsonElement> arguments)
    {
        foreach (var spec in descriptor.Arguments)
        {
            if (!arguments.TryGetValue(spec.Name, out var value))
            {
                if (spec.Required)
                    return $"Missing required argument '{spec.Name}'.";
                continue;
            }

            var valid = spec.Type switch
            {
                AssistantArgumentType.String => value.ValueKind == JsonValueKind.String,
                AssistantArgumentType.Integer => value.ValueKind == JsonValueKind.Number &&
                                                 value.TryGetInt64(out _),
                AssistantArgumentType.Boolean => value.ValueKind is JsonValueKind.True or JsonValueKind.False,
                _ => false
            };
            if (!valid)
                return $"Argument '{spec.Name}' has the wrong type.";
        }

        var known = descriptor.Arguments
            .Select(argument => argument.Name)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var unknown = arguments.Keys.FirstOrDefault(key => !known.Contains(key));
        return unknown is null ? null : $"Unknown argument '{unknown}'.";
    }

    private static CommandRun CreateRun(
        AssistantRequest request,
        IAssistantCommand? command,
        IReadOnlyDictionary<string, JsonElement>? arguments,
        ExecutionModifiers modifiers,
        DateTimeOffset intendedAt,
        RunState state,
        string? reason,
        DateTimeOffset now,
        string? hostId = null)
    {
        var copiedArguments = arguments?.ToDictionary(
            pair => pair.Key,
            pair => pair.Value,
            StringComparer.OrdinalIgnoreCase);

        return new CommandRun(
            Guid.NewGuid(),
            request.RequestId,
            command?.Descriptor.Id,
            command?.Descriptor.Version,
            copiedArguments,
            modifiers,
            hostId ?? (command?.Descriptor.Host switch
            {
                AssistantExecutionHost.Android => "android",
                AssistantExecutionHost.Either => "either",
                _ => "desktop"
            }),
            intendedAt,
            state,
            reason,
            null,
            now,
            request.Source,
            request.SourceItemId,
            request.Text,
            request.OccurredAt,
            request.ReceivedAt,
            request.ConversationId,
            request.DryRun);
    }
}
