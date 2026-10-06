namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class AssistantCommandRegistry
{
    private readonly IReadOnlyList<IAssistantCommand> _commands;
    private readonly IReadOnlyDictionary<string, IAssistantCommand> _byId;

    public AssistantCommandRegistry(IEnumerable<IAssistantCommand> commands)
    {
        _commands = commands.ToList();

        var duplicate = _commands
            .GroupBy(command => command.Descriptor.Id, StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault(group => group.Count() > 1);
        if (duplicate is not null)
            throw new InvalidOperationException($"Duplicate assistant command id: {duplicate.Key}");

        _byId = _commands.ToDictionary(
            command => command.Descriptor.Id,
            StringComparer.OrdinalIgnoreCase);
    }

    public IReadOnlyList<AssistantCommandDescriptor> Descriptors
        => _commands.Select(command => command.Descriptor).ToList();

    public AssistantCommandMatch? TryResolve(string text)
    {
        foreach (var command in _commands)
        {
            if (command.TryParse(text, out var arguments))
                return new AssistantCommandMatch(command, arguments);
        }

        return null;
    }

    public bool TryGet(string commandId, out IAssistantCommand command)
        => _byId.TryGetValue(commandId, out command!);

    public Task<AssistantCommandExecutionResult> ExecuteAsync(
        AssistantCommandMatch match,
        CancellationToken cancellationToken)
        => match.Command.ExecuteAsync(match.Arguments, cancellationToken);
}
