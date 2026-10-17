using System.Text.Json;
using System.Text.RegularExpressions;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class DeviceCommandProvider : IAssistantCommandProvider
{
    private readonly AssistantDeviceRegistry _devices;

    public DeviceCommandProvider(AssistantDeviceRegistry devices)
    {
        _devices = devices;
    }

    public string Name => "connected-devices";

    public IReadOnlyList<AssistantCommandRejection> Rejected
        => Array.Empty<AssistantCommandRejection>();

    public IReadOnlyList<IAssistantCommand> GetCommands()
    {
        return _devices.GetConnected()
            .SelectMany(device => device.Commands)
            .GroupBy(command => command.Id, StringComparer.OrdinalIgnoreCase)
            .Select(group => group
                .OrderByDescending(command => command.Version)
                .First())
            .OrderBy(command => command.Id, StringComparer.OrdinalIgnoreCase)
            .Select(command => (IAssistantCommand)new RemoteAdvertisedCommand(command))
            .ToList();
    }

    private sealed class RemoteAdvertisedCommand : IAssistantCommand
    {
        private readonly IReadOnlyList<Regex> _patterns;

        public RemoteAdvertisedCommand(DeviceAssistantCommandDescriptor source)
        {
            _patterns = source.Patterns
                .Select(pattern => new Regex(
                    pattern,
                    RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
                .ToList();

            Descriptor = new AssistantCommandDescriptor(
                source.Id,
                source.Version,
                source.Description,
                AssistantExecutionHost.Android,
                source.Arguments.Select(argument => new AssistantArgumentSpec(
                    argument.Name,
                    ParseArgumentType(argument.Type),
                    argument.Required,
                    argument.Description)).ToList(),
                source.Examples.Select(example => new AssistantCommandExample(
                    example.Utterance,
                    example.Arguments)).ToList(),
                source.AcceptsRelativeTime
                    ? AssistantTimingSupport.RelativeTime
                    : AssistantTimingSupport.None,
                new AssistantStalenessPolicy(
                    source.MaxDelayMillis is null
                        ? null
                        : TimeSpan.FromMilliseconds(source.MaxDelayMillis.Value)),
                new AssistantRiskPolicy(
                    ParseRisk(source.Risk),
                    ParseTrust(source.MinTrustWithoutConfirmation)),
                new AssistantExecutionPolicy(
                    TimeSpan.FromSeconds(Math.Clamp(source.TimeoutSeconds, 1, 3600)),
                    source.ConcurrencyKey,
                    source.Idempotent));
        }

        public AssistantCommandDescriptor Descriptor { get; }

        public AssistantParseResult? TryParse(AssistantParseInput input)
        {
            foreach (var pattern in _patterns)
            {
                var match = pattern.Match(input.Text.Trim());
                if (!match.Success)
                    continue;

                var arguments = new Dictionary<string, JsonElement>(
                    StringComparer.OrdinalIgnoreCase);
                foreach (var spec in Descriptor.Arguments)
                {
                    var group = match.Groups[spec.Name];
                    if (!group.Success || string.IsNullOrWhiteSpace(group.Value))
                        continue;

                    arguments[spec.Name] = spec.Type switch
                    {
                        AssistantArgumentType.Integer when long.TryParse(group.Value, out var number)
                            => JsonSerializer.SerializeToElement(number),
                        AssistantArgumentType.Boolean when bool.TryParse(group.Value, out var boolean)
                            => JsonSerializer.SerializeToElement(boolean),
                        _ => AssistantJson.String(group.Value.Trim())
                    };
                }

                return new AssistantParseResult(
                    arguments,
                    900 + match.Length);
            }

            return null;
        }

        public Task<AssistantCommandOutcome> ExecuteAsync(
            AssistantCommandInvocation invocation,
            CancellationToken cancellationToken)
            => throw new InvalidOperationException(
                "Remote advertised commands are dispatched by AssistantRemoteDispatcher.");

        private static AssistantArgumentType ParseArgumentType(string value)
            => Enum.TryParse<AssistantArgumentType>(value, true, out var parsed)
                ? parsed
                : AssistantArgumentType.String;

        private static AssistantRiskLevel ParseRisk(string value)
            => Enum.TryParse<AssistantRiskLevel>(value, true, out var parsed)
                ? parsed
                : AssistantRiskLevel.Low;

        private static AssistantTrustLevel ParseTrust(string value)
            => Enum.TryParse<AssistantTrustLevel>(value, true, out var parsed)
                ? parsed
                : AssistantTrustLevel.Low;
    }
}
