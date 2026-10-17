namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed class AssistantHostSelector
{
    private readonly AssistantDeviceRegistry _devices;

    public AssistantHostSelector(AssistantDeviceRegistry devices)
    {
        _devices = devices;
    }

    public string? SelectHost(IAssistantCommand command)
    {
        return command.Descriptor.Host switch
        {
            AssistantExecutionHost.Desktop => "desktop",
            AssistantExecutionHost.Either => "desktop",
            AssistantExecutionHost.Android => _devices.FindHost(command.Descriptor.Id) is { } device
                ? $"android:{device.DeviceId}"
                : null,
            _ => null
        };
    }

    public static bool IsRemote(string hostId)
        => hostId.StartsWith("android:", StringComparison.OrdinalIgnoreCase);

    public static string DeviceIdFromHost(string hostId)
        => IsRemote(hostId)
            ? hostId["android:".Length..]
            : throw new ArgumentException($"Host '{hostId}' is not remote.", nameof(hostId));
}
