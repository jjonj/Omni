using System.Collections.Concurrent;
using System.IO;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed record AssistantCommandRejection(
    string Provider,
    string? CommandId,
    string Reason);

public interface IAssistantCommandProvider
{
    string Name { get; }
    IReadOnlyList<IAssistantCommand> GetCommands();
    IReadOnlyList<AssistantCommandRejection> Rejected { get; }
}

public sealed class AssistantCommandCatalog
{
    private static readonly Regex IdPattern =
        new(@"^[a-z]+(?:\.[a-z_]+)+$", RegexOptions.CultureInvariant);

    private readonly IReadOnlyList<IAssistantCommandProvider> _providers;
    private readonly ILogger<AssistantCommandCatalog> _logger;
    private readonly ConcurrentDictionary<string, string> _runtimeRejected =
        new(StringComparer.OrdinalIgnoreCase);

    public AssistantCommandCatalog(
        IEnumerable<IAssistantCommandProvider> providers,
        ILogger<AssistantCommandCatalog> logger)
    {
        _providers = providers.ToList();
        _logger = logger;
    }

    public IReadOnlyList<AssistantCommandDescriptor> Descriptors
        => Snapshot().Commands.Select(command => command.Descriptor).ToList();

    public IReadOnlyList<AssistantCommandRejection> Rejected
        => Snapshot().Rejected;

    public AssistantRouteResult Resolve(
        string text,
        Func<IAssistantCommand, bool>? predicate = null)
    {
        var candidates = new List<AssistantCommandMatch>();
        foreach (var command in Snapshot().Commands)
        {
            if (predicate is not null && !predicate(command))
                continue;

            try
            {
                var parsed = command.TryParse(new AssistantParseInput(text));
                if (parsed is not null)
                    candidates.Add(new AssistantCommandMatch(command, parsed.Arguments, parsed.Specificity));
            }
            catch (Exception ex)
            {
                _runtimeRejected.TryAdd(
                    command.Descriptor.Id,
                    $"TryParse failed: {ex.GetType().Name}: {ex.Message}");
                _logger.LogError(ex, "[Assistant] Quarantined command {CommandId} after TryParse failure.", command.Descriptor.Id);
            }
        }

        if (candidates.Count == 0)
            return new AssistantRouteResult.None();

        var bestSpecificity = candidates.Max(candidate => candidate.Specificity);
        var best = candidates
            .Where(candidate => candidate.Specificity == bestSpecificity)
            .ToList();

        return best.Count == 1
            ? new AssistantRouteResult.Matched(best[0])
            : new AssistantRouteResult.Ambiguous(best);
    }

    public bool TryGet(string commandId, out IAssistantCommand command)
    {
        command = Snapshot().Commands.FirstOrDefault(candidate =>
            string.Equals(candidate.Descriptor.Id, commandId, StringComparison.OrdinalIgnoreCase))!;
        return command is not null;
    }

    private CatalogSnapshot Snapshot()
    {
        var active = new List<IAssistantCommand>();
        var rejected = new List<AssistantCommandRejection>();
        var ids = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var provider in _providers)
        {
            IReadOnlyList<IAssistantCommand> commands;
            try
            {
                commands = provider.GetCommands();
            }
            catch (Exception ex)
            {
                rejected.Add(new(provider.Name, null, $"Provider failed: {ex.Message}"));
                continue;
            }

            rejected.AddRange(provider.Rejected);
            foreach (var command in commands)
            {
                var id = command.Descriptor.Id;
                var reason = Validate(command.Descriptor);
                if (reason is null && _runtimeRejected.TryGetValue(id, out var runtimeReason))
                    reason = runtimeReason;
                if (reason is null && !ids.Add(id))
                    reason = $"Duplicate command id '{id}'.";

                if (reason is not null)
                {
                    rejected.Add(new(provider.Name, id, reason));
                    continue;
                }

                active.Add(command);
            }
        }

        return new CatalogSnapshot(active, rejected);
    }

    private static string? Validate(AssistantCommandDescriptor descriptor)
    {
        if (!IdPattern.IsMatch(descriptor.Id))
            return $"Invalid command id '{descriptor.Id}'.";

        var argumentNames = descriptor.Arguments
            .Select(argument => argument.Name)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        foreach (var example in descriptor.Examples)
        {
            if (example.Arguments.Keys.Any(key => !argumentNames.Contains(key)))
                return $"Example '{example.Utterance}' contains an undeclared argument.";
        }

        foreach (var file in descriptor.Execution.RequiredFiles ?? Array.Empty<string>())
        {
            var fullPath = Path.IsPathRooted(file)
                ? file
                : Path.Combine(AppContext.BaseDirectory, file);
            if (!File.Exists(fullPath))
                return $"Required file is missing: {file}";
        }

        return null;
    }

    private sealed record CatalogSnapshot(
        IReadOnlyList<IAssistantCommand> Commands,
        IReadOnlyList<AssistantCommandRejection> Rejected);
}
