using System.Collections.Concurrent;
using System.Net;

namespace OmniSync.Hub.Logic.Services;

public sealed record AndroidDeploymentStatus(
    string RequestId,
    string Status,
    string Message,
    DateTimeOffset UpdatedAtUtc);

public sealed class AndroidUpdateDeploymentCoordinator
{
    private sealed class DeploymentEntry
    {
        public AndroidDeploymentStatus Status { get; set; } = null!;
        public TaskCompletionSource<AndroidDeploymentStatus> FirstReport { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    private readonly ConcurrentDictionary<string, DeploymentEntry> _deployments = new();
    private readonly ConcurrentDictionary<string, bool> _connectedClients = new();

    public int ConnectedClientCount => _connectedClients.Count;
    public int RemoteClientCount => _connectedClients.Count(client => client.Value);

    public void ClientConnected(string connectionId, string? remoteAddress)
    {
        var isRemote = IPAddress.TryParse(remoteAddress, out var address) &&
                       !IPAddress.IsLoopback(address);
        _connectedClients[connectionId] = isRemote;
    }

    public void ClientDisconnected(string connectionId) => _connectedClients.TryRemove(connectionId, out _);

    public string Begin()
    {
        RemoveExpiredEntries();
        var requestId = Guid.NewGuid().ToString("N");
        _deployments[requestId] = new DeploymentEntry
        {
            Status = new AndroidDeploymentStatus(
                requestId,
                "dispatched",
                "Waiting for an Android client.",
                DateTimeOffset.UtcNow)
        };
        return requestId;
    }

    public bool Report(string requestId, string status, string message)
    {
        if (!_deployments.TryGetValue(requestId, out var entry)) return false;

        var snapshot = new AndroidDeploymentStatus(
            requestId,
            status,
            message,
            DateTimeOffset.UtcNow);
        entry.Status = snapshot;
        entry.FirstReport.TrySetResult(snapshot);
        return true;
    }

    public AndroidDeploymentStatus? Get(string requestId) =>
        _deployments.TryGetValue(requestId, out var entry) ? entry.Status : null;

    public async Task<AndroidDeploymentStatus?> WaitForFirstReportAsync(
        string requestId,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        if (!_deployments.TryGetValue(requestId, out var entry)) return null;
        try
        {
            return await entry.FirstReport.Task.WaitAsync(timeout, cancellationToken);
        }
        catch (TimeoutException)
        {
            return null;
        }
    }

    private void RemoveExpiredEntries()
    {
        var cutoff = DateTimeOffset.UtcNow.AddHours(-1);
        foreach (var entry in _deployments)
        {
            if (entry.Value.Status.UpdatedAtUtc < cutoff)
            {
                _deployments.TryRemove(entry.Key, out _);
            }
        }
    }
}
