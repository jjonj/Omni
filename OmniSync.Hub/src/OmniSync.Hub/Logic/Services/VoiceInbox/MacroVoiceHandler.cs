namespace OmniSync.Hub.Logic.Services.VoiceInbox;

/// <summary>
/// "notepad" / "run pc sleep" / "activate notepad": runs the Voice macro with that name,
/// so free-text sources reach the same commands as Google Home scenes.
/// </summary>
public sealed class MacroVoiceHandler : IVoiceTextHandler
{
    private static readonly string[] LeadIns = { "run ", "activate ", "start ", "do " };
    private readonly GoogleHomeService _googleHome;
    private readonly IMacroService _macroService;

    public MacroVoiceHandler(GoogleHomeService googleHome, IMacroService macroService)
    {
        _googleHome = googleHome;
        _macroService = macroService;
    }

    public string Name => "Macro";

    public Task<VoiceHandlerResult?> TryHandleAsync(VoiceInboxItem item, CancellationToken cancellationToken)
    {
        var text = item.Text.Trim().TrimEnd('.', '!').ToLowerInvariant();
        foreach (var lead in LeadIns)
        {
            if (text.StartsWith(lead)) { text = text[lead.Length..].Trim(); break; }
        }
        var macro = _googleHome.GetVoiceMacros().FirstOrDefault(m => string.Equals(m.Name.Trim(), text, StringComparison.OrdinalIgnoreCase));
        if (macro is null) return Task.FromResult<VoiceHandlerResult?>(null);

        _ = Task.Run(() => _macroService.ExecuteMacroAsync(macro), CancellationToken.None);
        return Task.FromResult<VoiceHandlerResult?>(new VoiceHandlerResult($"Ran macro {macro.Name}"));
    }
}
