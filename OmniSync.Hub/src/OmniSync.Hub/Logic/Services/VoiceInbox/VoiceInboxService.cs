using System.IO;
using System.Text.Json;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Presentation.Hubs;

namespace OmniSync.Hub.Logic.Services.VoiceInbox;

/// <summary>A free-text command spoken (or typed) somewhere and delivered to the Hub.</summary>
public sealed record VoiceInboxItem(
    string Text,
    string Source,
    string? SourceId,
    DateTimeOffset ReceivedAt,
    bool IsBacklog = false,
    DateTimeOffset? OccurredAt = null,
    Guid? RequestId = null,
    string? ConversationId = null,
    bool DryRun = false);

/// <summary>Outcome of a handler that took responsibility for an item.</summary>
public sealed record VoiceHandlerResult(
    string Summary,
    Guid? RunId = null,
    string? RunState = null,
    string? CommandId = null,
    string? Decision = null);

/// <summary>
/// One step in the voice inbox pipeline. Handlers run in registration order; the first one that
/// returns a result owns the item. Return null to pass the item on.
/// </summary>
public interface IVoiceTextHandler
{
    string Name { get; }
    Task<VoiceHandlerResult?> TryHandleAsync(VoiceInboxItem item, CancellationToken cancellationToken);
}

/// <summary>
/// Free-text command pipeline. Sources (Google Keep "Omni" list today; phone, calendar, assistant later)
/// call <see cref="SubmitAsync"/>; registered <see cref="IVoiceTextHandler"/>s decide what to do.
/// Unhandled items are kept in the history so nothing spoken is lost; the future Omni assistant
/// handler goes last in the pipeline and will pick up everything the pattern handlers don't.
/// History: %APPDATA%\OmniSync\voice_inbox.jsonl.
/// </summary>
public sealed class VoiceInboxService
{
    private readonly IReadOnlyList<IVoiceTextHandler> _handlers;
    private readonly HubMonitorService _monitorService;
    private readonly IHubContext<RpcApiHub> _hubContext;
    private readonly ILogger<VoiceInboxService> _logger;
    private readonly string _historyPath;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public VoiceInboxService(
        IEnumerable<IVoiceTextHandler> handlers,
        HubMonitorService monitorService,
        IHubContext<RpcApiHub> hubContext,
        ILogger<VoiceInboxService> logger)
    {
        _handlers = handlers.ToList();
        _monitorService = monitorService;
        _hubContext = hubContext;
        _logger = logger;
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync");
        Directory.CreateDirectory(dir);
        _historyPath = Path.Combine(dir, "voice_inbox.jsonl");
    }

    public async Task<VoiceInboxOutcome> SubmitAsync(VoiceInboxItem item, CancellationToken cancellationToken = default)
    {
        var text = item.Text.Trim();
        item = item with { Text = text };
        _monitorService.AddLogMessage($"[VoiceInbox] ({item.Source}) \"{text}\"");

        VoiceInboxOutcome outcome = new("unhandled", null, "No handler matched.");
        foreach (var handler in _handlers)
        {
            try
            {
                var result = await handler.TryHandleAsync(item, cancellationToken);
                if (result is null) continue;
                outcome = new VoiceInboxOutcome(
                    "handled",
                    handler.Name,
                    result.Summary,
                    result.RunId,
                    result.RunState,
                    result.CommandId,
                    result.Decision);
                break;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "[VoiceInbox] Handler {Handler} failed for \"{Text}\"", handler.Name, text);
                outcome = new VoiceInboxOutcome("failed", handler.Name, ex.Message);
                break;
            }
        }

        _monitorService.AddLogMessage($"[VoiceInbox] {outcome.Status}{(outcome.Handler is null ? "" : $" by {outcome.Handler}")}: {outcome.Summary}");
        await AppendHistoryAsync(item, outcome);
        await _hubContext.Clients.All.SendAsync("VoiceInboxItem", new
        {
            item.Text,
            item.Source,
            item.ReceivedAt,
            item.IsBacklog,
            item.OccurredAt,
            item.RequestId,
            item.ConversationId,
            item.DryRun,
            outcome.Status,
            outcome.Handler,
            outcome.Summary,
            outcome.RunId,
            outcome.RunState,
            outcome.CommandId,
            outcome.Decision
        }, CancellationToken.None);
        return outcome;
    }

    private async Task AppendHistoryAsync(VoiceInboxItem item, VoiceInboxOutcome outcome)
    {
        await _gate.WaitAsync();
        try
        {
            var line = JsonSerializer.Serialize(new
            {
                item.ReceivedAt,
                item.Source,
                item.SourceId,
                item.Text,
                item.IsBacklog,
                item.OccurredAt,
                item.RequestId,
                item.ConversationId,
                item.DryRun,
                outcome.Status,
                outcome.Handler,
                outcome.Summary,
                outcome.RunId,
                outcome.RunState,
                outcome.CommandId,
                outcome.Decision
            });
            await File.AppendAllTextAsync(_historyPath, line + Environment.NewLine);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "[VoiceInbox] Could not write history");
        }
        finally
        {
            _gate.Release();
        }
    }
}

public sealed record VoiceInboxOutcome(
    string Status,
    string? Handler,
    string Summary,
    Guid? RunId = null,
    string? RunState = null,
    string? CommandId = null,
    string? Decision = null);
