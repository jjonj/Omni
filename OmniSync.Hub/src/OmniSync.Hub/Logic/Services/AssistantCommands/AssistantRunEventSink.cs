using System.Text.Json;
using System.IO;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Presentation.Hubs;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class AssistantRunEventSink
{
    private readonly IHubContext<RpcApiHub> _hubContext;
    private readonly HubMonitorService _monitor;
    private readonly ILogger<AssistantRunEventSink> _logger;
    private readonly string _eventPath;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public AssistantRunEventSink(
        IHubContext<RpcApiHub> hubContext,
        HubMonitorService monitor,
        ILogger<AssistantRunEventSink> logger)
    {
        _hubContext = hubContext;
        _monitor = monitor;
        _logger = logger;

        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "OmniSync");
        Directory.CreateDirectory(directory);
        _eventPath = Path.Combine(directory, "assistant_run_events.jsonl");
    }

    public async Task PublishAsync(CommandRun run)
    {
        _monitor.AddLogMessage(
            $"[Assistant] {run.RunId} {run.CommandId ?? "unhandled"} -> {run.State}" +
            (string.IsNullOrWhiteSpace(run.Summary) ? string.Empty : $": {run.Summary}"));

        await _gate.WaitAsync();
        try
        {
            var line = JsonSerializer.Serialize(new
            {
                run.UpdatedAt,
                run.RunId,
                run.RequestId,
                run.CommandId,
                run.CommandVersion,
                run.State,
                run.IntendedAt,
                run.Reason,
                run.Summary,
                run.Source,
                run.SourceItemId
            });
            await File.AppendAllTextAsync(
                _eventPath,
                line + Environment.NewLine);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "[Assistant] Could not append run event log.");
        }
        finally
        {
            _gate.Release();
        }

        try
        {
            await _hubContext.Clients.All.SendAsync(
                "AssistantRunUpdated",
                new
                {
                    runId = run.RunId,
                    requestId = run.RequestId,
                    commandId = run.CommandId,
                    state = run.State.ToString(),
                    run.Reason,
                    run.Summary,
                    run.UpdatedAt
                },
                CancellationToken.None);
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "[Assistant] Could not broadcast run update.");
        }
    }
}
