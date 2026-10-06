namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public enum AssistantExecutionHost
{
    Desktop,
    Android,
    Either
}

public enum AssistantArgumentType
{
    String,
    Integer,
    Boolean
}

public sealed record AssistantQueuePolicy(
    bool RunFromBacklog = true,
    TimeSpan? MaxLateness = null)
{
    public static AssistantQueuePolicy RunQueued { get; } = new();

    public static AssistantQueuePolicy SkipBacklog(TimeSpan? maxLateness = null)
        => new(false, maxLateness);
}

public sealed record AssistantCommandDescriptor(
    string Id,
    string Description,
    AssistantExecutionHost Host,
    IReadOnlyDictionary<string, AssistantArgumentType> Arguments,
    AssistantQueuePolicy QueuePolicy);

public sealed record AssistantCommandMatch(
    IAssistantCommand Command,
    IReadOnlyDictionary<string, string> Arguments);

public sealed record AssistantCommandExecutionResult(string Summary);

public interface IAssistantCommand
{
    AssistantCommandDescriptor Descriptor { get; }

    bool TryParse(
        string text,
        out IReadOnlyDictionary<string, string> arguments);

    Task<AssistantCommandExecutionResult> ExecuteAsync(
        IReadOnlyDictionary<string, string> arguments,
        CancellationToken cancellationToken);
}
