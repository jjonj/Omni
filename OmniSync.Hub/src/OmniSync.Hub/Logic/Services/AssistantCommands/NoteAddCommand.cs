using System.Text.RegularExpressions;
using System.IO;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed partial class NoteAddCommand : IAssistantCommand
{
    public const string InboxFileName = "Voice Inbox.md";
    private readonly string _vaultRoot;

    public NoteAddCommand()
        : this(@"B:\GDrive\SharedWithPhone\Obsidian\jjj")
    {
    }

    internal NoteAddCommand(string vaultRoot)
    {
        _vaultRoot = vaultRoot;
    }

    public AssistantCommandDescriptor Descriptor { get; } = new(
        "note.add",
        1,
        "Append a spoken note to the project or general Obsidian voice inbox.",
        AssistantExecutionHost.Desktop,
        new[]
        {
            new AssistantArgumentSpec("project", AssistantArgumentType.String, false, "Optional project name."),
            new AssistantArgumentSpec("text", AssistantArgumentType.String, true, "Note text.")
        },
        new[]
        {
            new AssistantCommandExample(
                "note buy milk",
                new Dictionary<string, string> { ["text"] = "buy milk" })
        },
        AssistantTimingSupport.None,
        AssistantStalenessPolicy.Unlimited,
        new AssistantRiskPolicy(AssistantRiskLevel.Low),
        new AssistantExecutionPolicy(
            TimeSpan.FromSeconds(15),
            "notes",
            Idempotent: false));

    [GeneratedRegex(
        @"^(?:(?:add|make|create|take)\s+(?:a\s+|an\s+|the\s+)?)?(?:(?<project>[\p{L}\p{N}][\p{L}\p{N} \-]{0,40}?)\s+)?notes?\b\s*(?:that\s+|saying\s+|to\s+|:\s*|-\s*)?(?<text>.+)$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex NotePattern();

    public AssistantParseResult? TryParse(AssistantParseInput input)
    {
        var match = NotePattern().Match(input.Text);
        if (!match.Success)
            return null;

        var text = match.Groups["text"].Value.Trim();
        if (text.Length == 0)
            return null;

        var args = new Dictionary<string, System.Text.Json.JsonElement>(
            StringComparer.OrdinalIgnoreCase)
        {
            ["text"] = AssistantJson.String(text)
        };

        if (match.Groups["project"].Success)
        {
            var project = match.Groups["project"].Value.Trim();
            if (project.Length > 0)
                args["project"] = AssistantJson.String(project);
        }

        return new AssistantParseResult(args, 800 + text.Length);
    }

    public async Task<AssistantCommandOutcome> ExecuteAsync(
        AssistantCommandInvocation invocation,
        CancellationToken cancellationToken)
    {
        var args = invocation.Run.Arguments
            ?? throw new InvalidOperationException("Note arguments are missing.");
        var project = AssistantJson.GetString(args, "project");
        var text = AssistantJson.GetString(args, "text");
        if (text.Length == 0)
            return new AssistantCommandOutcome.Failed("The note text was empty.");

        var folder = project.Length == 0 ? null : FindProjectFolder(project);
        var target = Path.Combine(folder ?? _vaultRoot, InboxFileName);
        var source = invocation.Request.Source.Kind.ToString();
        var timestamp = invocation.Request.ReceivedAt.LocalDateTime;
        var line = folder is null && project.Length > 0
            ? $"- [ ] **{project}**: {Capitalize(text)} _({timestamp:yyyy-MM-dd HH:mm}, {source})_"
            : $"- [ ] {Capitalize(text)} _({timestamp:yyyy-MM-dd HH:mm}, {source})_";

        if (!File.Exists(target))
        {
            await File.WriteAllTextAsync(
                target,
                "# Voice Inbox\n\nNotes added by voice through Omni. Check them off or move them once dealt with.\n\n",
                cancellationToken);
        }

        await File.AppendAllTextAsync(
            target,
            line + Environment.NewLine,
            cancellationToken);

        return new AssistantCommandOutcome.Succeeded(
            $"Added to {Path.GetRelativePath(_vaultRoot, target)}: {text}");
    }

    private string? FindProjectFolder(string project)
    {
        if (!Directory.Exists(_vaultRoot))
            return null;

        var wanted = Normalize(project);
        foreach (var directory in Directory.EnumerateDirectories(_vaultRoot))
        {
            if (Normalize(Path.GetFileName(directory)) == wanted)
                return directory;

            try
            {
                foreach (var child in Directory.EnumerateDirectories(directory))
                {
                    if (Normalize(Path.GetFileName(child)) == wanted)
                        return child;
                }
            }
            catch
            {
                // A single unreadable folder should not prevent note capture elsewhere.
            }
        }

        return null;
    }

    private static string Normalize(string value)
        => new(value
            .Where(char.IsLetterOrDigit)
            .Select(char.ToLowerInvariant)
            .ToArray());

    private static string Capitalize(string text)
        => text.Length == 0 ? text : char.ToUpper(text[0]) + text[1..];
}
