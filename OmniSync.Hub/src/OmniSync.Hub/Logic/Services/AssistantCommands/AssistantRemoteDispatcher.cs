using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Presentation.Hubs;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed record RemoteAssistantInvocation(
    string RunId,
    string CommandId,
    Dictionary<string, object?> Arguments,
    long? RunAtUnixMillis);

public sealed class AssistantRemoteDispatcher
{
    private readonly AssistantDeviceRegistry _devices;
    private readonly IHubContext<RpcApiHub> _hub;
    private readonly AssistantRunStore _store;
    private readonly AssistantRunEventSink _events;
    private readonly TimeProvider _timeProvider;

    public AssistantRemoteDispatcher(
        AssistantDeviceRegistry devices,
        IHubContext<RpcApiHub> hub,
        AssistantRunStore store,
        AssistantRunEventSink events,
        TimeProvider timeProvider)
    {
        _devices = devices;
        _hub = hub;
        _store = store;
        _events = events;
        _timeProvider = timeProvider;
    }

    public async Task<bool> DispatchAsync(
        CommandRun run,
        bool schedule,
        CancellationToken cancellationToken = default)
    {
        if (run.CommandId is null || !AssistantHostSelector.IsRemote(run.HostId))
            return false;

        var deviceId = AssistantHostSelector.DeviceIdFromHost(run.HostId);
        var device = _devices.GetDevice(deviceId);
        if (device is null)
            return false;

        var arguments = run.Arguments?.ToDictionary(
                pair => pair.Key,
                pair => JsonValue(pair.Value),
                StringComparer.OrdinalIgnoreCase)
            ?? new Dictionary<string, object?>(StringComparer.OrdinalIgnoreCase);

        var invocation = new RemoteAssistantInvocation(
            run.RunId.ToString(),
            run.CommandId,
            arguments,
            schedule ? run.IntendedAt.ToUnixTimeMilliseconds() : null);

        await _hub.Clients.Client(device.ConnectionId).SendAsync(
            schedule ? "ScheduleAssistantCommand" : "ExecuteAssistantCommand",
            invocation,
            cancellationToken);
        return true;
    }

    public async Task<bool> ReportResultAsync(
        Guid runId,
        string state,
        string? summary)
    {
        var run = _store.Get(runId);
        if (run is null || !AssistantHostSelector.IsRemote(run.HostId))
            return false;

        var parsedState = state.Trim().ToLowerInvariant() switch
        {
            "scheduled" => RunState.Scheduled,
            "running" => RunState.Running,
            "succeeded" => RunState.Succeeded,
            "failed" => RunState.Failed,
            "skipped" => RunState.Skipped,
            "cancelled" => RunState.Cancelled,
            _ => RunState.Failed
        };

        if (run.State is RunState.Succeeded or RunState.Failed or RunState.Skipped or RunState.Cancelled)
            return true;

        var updated = _store.Update(runId, current => current with
        {
            State = parsedState,
            Reason = parsedState == RunState.Succeeded ? null : summary,
            Summary = summary,
            UpdatedAt = _timeProvider.GetUtcNow()
        });
        await _events.PublishAsync(updated);
        return true;
    }

    private static object? JsonValue(System.Text.Json.JsonElement value)
        => value.ValueKind switch
        {
            System.Text.Json.JsonValueKind.String => value.GetString(),
            System.Text.Json.JsonValueKind.Number when value.TryGetInt64(out var number) => number,
            System.Text.Json.JsonValueKind.True => true,
            System.Text.Json.JsonValueKind.False => false,
            System.Text.Json.JsonValueKind.Null => null,
            _ => value.GetRawText()
        };
}
