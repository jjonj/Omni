using System.IO;
using System.Text.RegularExpressions;

namespace OmniSync.Hub.Logic.Services.VoiceInbox;

/// <summary>
/// "wartribes note implement dagger attacks" / "add a wartribes note that ..." / "note buy milk".
/// Appends a checklist line to "Voice Inbox.md" in the project's Obsidian folder
/// (a vault folder whose name matches the project), or in the vault root when there is no project.
/// </summary>
public sealed partial class NoteVoiceHandler : IVoiceTextHandler
{
    public const string InboxFileName = "Voice Inbox.md";
    private readonly string _vaultRoot;

    public NoteVoiceHandler() : this(@"B:\GDrive\SharedWithPhone\Obsidian\jjj") { }

    public NoteVoiceHandler(string vaultRoot) => _vaultRoot = vaultRoot;

    public string Name => "Note";

    // Optional "add (a|an|the)" lead-in, optional project words, the word note, optional "that"/"saying", then the text.
    [GeneratedRegex(@"^(?:(?:add|make|create|take)\s+(?:a\s+|an\s+|the\s+)?)?(?:(?<project>[\p{L}\p{N}][\p{L}\p{N} \-]{0,40}?)\s+)?notes?\b\s*(?:that\s+|saying\s+|to\s+|:\s*|-\s*)?(?<text>.+)$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex NotePattern();

    public async Task<VoiceHandlerResult?> TryHandleAsync(VoiceInboxItem item, CancellationToken cancellationToken)
    {
        var match = NotePattern().Match(item.Text);
        if (!match.Success) return null;

        var project = match.Groups["project"].Success ? match.Groups["project"].Value.Trim() : "";
        var text = match.Groups["text"].Value.Trim();
        if (text.Length == 0) return null;

        var folder = string.IsNullOrEmpty(project) ? null : FindProjectFolder(project);
        var target = Path.Combine(folder ?? _vaultRoot, InboxFileName);
        var line = folder is null && project.Length > 0
            ? $"- [ ] **{project}**: {Capitalize(text)} _({item.ReceivedAt.LocalDateTime:yyyy-MM-dd HH:mm}, {item.Source})_"
            : $"- [ ] {Capitalize(text)} _({item.ReceivedAt.LocalDateTime:yyyy-MM-dd HH:mm}, {item.Source})_";

        if (!File.Exists(target))
        {
            await File.WriteAllTextAsync(target, "# Voice Inbox\n\nNotes added by voice through Omni. Check them off or move them once dealt with.\n\n", cancellationToken);
        }
        await File.AppendAllTextAsync(target, line + "\n", cancellationToken);
        return new VoiceHandlerResult($"Added to {Path.GetRelativePath(_vaultRoot, target)}: {text}");
    }

    /// <summary>Vault folder whose name matches the spoken project (top level, then one level down).</summary>
    public string? FindProjectFolder(string project)
    {
        if (!Directory.Exists(_vaultRoot)) return null;
        var wanted = Normalize(project);
        IEnumerable<string> Candidates()
        {
            foreach (var dir in Directory.EnumerateDirectories(_vaultRoot)) yield return dir;
            foreach (var dir in Directory.EnumerateDirectories(_vaultRoot))
            {
                IEnumerable<string> children;
                try { children = Directory.EnumerateDirectories(dir).ToList(); } catch { continue; }
                foreach (var child in children) yield return child;
            }
        }
        return Candidates().FirstOrDefault(d => Normalize(Path.GetFileName(d)) == wanted);
    }

    private static string Normalize(string value) => new(value.Where(char.IsLetterOrDigit).Select(char.ToLowerInvariant).ToArray());

    private static string Capitalize(string text) => text.Length == 0 ? text : char.ToUpper(text[0]) + text[1..];
}
