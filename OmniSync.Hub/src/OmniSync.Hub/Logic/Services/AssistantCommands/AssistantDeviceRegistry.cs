using System.Collections.Concurrent;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed record DeviceAssistantArgument(
    string Name,
    string Type,
    bool Required,
    string Description);

public sealed record DeviceAssistantExample(
    string Utterance,
    Dictionary<string, string> Arguments);

public sealed record DeviceAssistantCommandDescriptor(
    string Id,
    int Version,
    string Description,
    List<string> Patterns,
    List<DeviceAssistantArgument> Arguments,
    List<DeviceAssistantExample> Examples,
    bool AcceptsRelativeTime,
    long? MaxDelayMillis,
    string Risk,
    string MinTrustWithoutConfirmation,
    bool Idempotent,
    int TimeoutSeconds,
    string? ConcurrencyKey);

public sealed record AssistantDeviceAdvertisement(
    string DeviceId,
    string ConnectionId,
    DateTimeOffset UpdatedAt,
    IReadOnlyList<DeviceAssistantCommandDescriptor> Commands);

public sealed class AssistantDeviceRegistry
{
    private readonly ConcurrentDictionary<string, AssistantDeviceAdvertisement> _devices =
        new(StringComparer.OrdinalIgnoreCase);

    public void Advertise(
        string deviceId,
        string connectionId,
        IReadOnlyList<DeviceAssistantCommandDescriptor> commands)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
            throw new ArgumentException("Device id is required.", nameof(deviceId));

        _devices[deviceId] = new AssistantDeviceAdvertisement(
            deviceId,
            connectionId,
            DateTimeOffset.UtcNow,
            commands.ToList());
    }

    public void Disconnect(string connectionId)
    {
        foreach (var pair in _devices)
        {
            if (!string.Equals(
                    pair.Value.ConnectionId,
                    connectionId,
                    StringComparison.Ordinal))
                continue;

            _devices.TryRemove(pair.Key, out _);
        }
    }

    public IReadOnlyList<AssistantDeviceAdvertisement> GetConnected()
        => _devices.Values
            .OrderBy(device => device.DeviceId, StringComparer.OrdinalIgnoreCase)
            .ToList();

    public AssistantDeviceAdvertisement? FindHost(string commandId)
        => _devices.Values
            .Where(device => device.Commands.Any(command =>
                string.Equals(command.Id, commandId, StringComparison.OrdinalIgnoreCase)))
            .OrderByDescending(device => device.UpdatedAt)
            .FirstOrDefault();

    public AssistantDeviceAdvertisement? GetDevice(string deviceId)
        => _devices.TryGetValue(deviceId, out var device) ? device : null;
}
