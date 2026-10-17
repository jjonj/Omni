using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class ReflectionCommandProvider : IAssistantCommandProvider
{
    private readonly IReadOnlyList<IAssistantCommand> _commands;
    private readonly IReadOnlyList<AssistantCommandRejection> _rejected;

    public ReflectionCommandProvider(
        IServiceProvider services,
        ILogger<ReflectionCommandProvider> logger)
    {
        var commands = new List<IAssistantCommand>();
        var rejected = new List<AssistantCommandRejection>();

        var commandTypes = typeof(AssistantCommandRegistration).Assembly
            .GetTypes()
            .Where(type =>
                type.IsClass &&
                !type.IsAbstract &&
                (type.IsPublic || type.IsNestedPublic) &&
                typeof(IAssistantCommand).IsAssignableFrom(type))
            .OrderBy(type => type.FullName, StringComparer.Ordinal)
            .ToList();

        foreach (var commandType in commandTypes)
        {
            try
            {
                commands.Add((IAssistantCommand)ActivatorUtilities.CreateInstance(
                    services,
                    commandType));
            }
            catch (Exception ex)
            {
                rejected.Add(new(
                    Name,
                    commandType.FullName,
                    $"Construction failed: {ex.GetType().Name}: {ex.Message}"));
                logger.LogError(
                    ex,
                    "[Assistant] Command module {CommandType} was quarantined during discovery.",
                    commandType.FullName);
            }
        }

        _commands = commands;
        _rejected = rejected;
    }

    public string Name => "reflection";

    public IReadOnlyList<AssistantCommandRejection> Rejected => _rejected;

    public IReadOnlyList<IAssistantCommand> GetCommands() => _commands;
}
