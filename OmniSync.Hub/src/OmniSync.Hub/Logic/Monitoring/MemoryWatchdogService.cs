using System;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Logic.Monitoring;

public sealed class MemoryWatchdogService : BackgroundService
{
    private const long ThresholdBytes = 1024L * 1024 * 1024;
    private static readonly TimeSpan SampleInterval = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan HighMemoryReportInterval = TimeSpan.FromMinutes(1);

    private readonly ILogger<MemoryWatchdogService> _logger;
    private readonly AiCliService _aiCliService;
    private readonly RemoteTerminalSessionManager _remoteTerminalSessions;
    private readonly HubMonitorService _hubMonitor;
    private bool _isAboveThreshold;
    private DateTime _lastHighMemoryReportUtc;
    private long _peakPrivateBytes;
    private long _peakWorkingSetBytes;

    public MemoryWatchdogService(
        ILogger<MemoryWatchdogService> logger,
        AiCliService aiCliService,
        RemoteTerminalSessionManager remoteTerminalSessions,
        HubMonitorService hubMonitor)
    {
        _logger = logger;
        _aiCliService = aiCliService;
        _remoteTerminalSessions = remoteTerminalSessions;
        _hubMonitor = hubMonitor;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(SampleInterval);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                DiagnoseMemory();
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Memory watchdog could not collect its diagnostic snapshot.");
            }
        }
    }

    private void DiagnoseMemory()
    {
        using var process = Process.GetCurrentProcess();
        process.Refresh();

        long privateBytes = process.PrivateMemorySize64;
        long workingSetBytes = process.WorkingSet64;
        bool isAboveThreshold = privateBytes >= ThresholdBytes || workingSetBytes >= ThresholdBytes;

        if (!isAboveThreshold)
        {
            if (_isAboveThreshold)
            {
                _logger.LogWarning(
                    "[MemoryWatchdog] Memory recovered below 1 GiB. peakPrivate={PeakPrivateMiB:F1} MiB peakWorkingSet={PeakWorkingSetMiB:F1} MiB currentPrivate={PrivateMiB:F1} MiB currentWorkingSet={WorkingSetMiB:F1} MiB",
                    ToMiB(_peakPrivateBytes), ToMiB(_peakWorkingSetBytes), ToMiB(privateBytes), ToMiB(workingSetBytes));
            }

            _isAboveThreshold = false;
            _peakPrivateBytes = 0;
            _peakWorkingSetBytes = 0;
            return;
        }

        _peakPrivateBytes = Math.Max(_peakPrivateBytes, privateBytes);
        _peakWorkingSetBytes = Math.Max(_peakWorkingSetBytes, workingSetBytes);
        DateTime now = DateTime.UtcNow;
        if (_isAboveThreshold && now - _lastHighMemoryReportUtc < HighMemoryReportInterval) return;

        _isAboveThreshold = true;
        _lastHighMemoryReportUtc = now;
        LogDiagnosticSnapshot(process, privateBytes, workingSetBytes);
    }

    private void LogDiagnosticSnapshot(Process process, long privateBytes, long workingSetBytes)
    {
        var gcInfo = GC.GetGCMemoryInfo();
        var ai = _aiCliService.GetMemoryDiagnosticSnapshot();
        var remoteTerminal = _remoteTerminalSessions.GetMemoryDiagnosticSnapshot();
        int hubTcpConnections = CountHubTcpConnections();
        int monitorLogCount = _hubMonitor.LogMessages.Count;

        _logger.LogWarning(
            "[MemoryWatchdog] 1 GiB threshold exceeded. private={PrivateMiB:F1} MiB workingSet={WorkingSetMiB:F1} MiB managedHeap={ManagedHeapMiB:F1} MiB managedFragmented={ManagedFragmentedMiB:F1} MiB gcCommitted={GcCommittedMiB:F1} MiB threads={ThreadCount} handles={HandleCount} hubTcpConnections={HubTcpConnections} monitorLogs={MonitorLogCount}",
            ToMiB(privateBytes), ToMiB(workingSetBytes), ToMiB(gcInfo.HeapSizeBytes), ToMiB(gcInfo.FragmentedBytes), ToMiB(gcInfo.TotalCommittedBytes), process.Threads.Count, process.HandleCount, hubTcpConnections, monitorLogCount);

        _logger.LogWarning(
            "[MemoryWatchdog] AI state: sessions={SessionCount} broadcastMessages={BroadcastMessageCount} broadcastChars={BroadcastCharacters} ({BroadcastMiB:F1} MiB UTF-16 lower bound) historyChars={HistoryCharacters} ({HistoryMiB:F1} MiB) outgoingPrompts={OutgoingPromptCount} outgoingPromptChars={OutgoingPromptCharacters} pendingPrompts={PendingPromptCount} pendingPromptChars={PendingPromptCharacters}",
            ai.SessionCount, ai.BroadcastMessageCount, ai.BroadcastMessageCharacters, ToMiB(ai.BroadcastMessageCharacters * sizeof(char)), ai.HistoryBufferCharacters, ToMiB(ai.HistoryBufferCharacters * sizeof(char)), ai.OutgoingPromptCount, ai.OutgoingPromptCharacters, ai.PendingPromptCount, ai.PendingPromptCharacters);

        _logger.LogWarning(
            "[MemoryWatchdog] Remote terminal state: sessions={SessionCount} connectedHosts={ConnectedHostCount} exitedChildProcessesStillRetained={ExitedProcessCount}",
            remoteTerminal.SessionCount, remoteTerminal.ConnectedHostCount, remoteTerminal.ExitedProcessCount);

        long knownManagedBytes = ai.BroadcastMessageCharacters * sizeof(char)
            + ai.HistoryBufferCharacters * sizeof(char)
            + ai.OutgoingPromptCharacters * sizeof(char)
            + ai.PendingPromptCharacters * sizeof(char);
        string leadingSignal = knownManagedBytes >= 64L * 1024 * 1024
            ? "application buffers account for a material amount of retained text"
            : gcInfo.HeapSizeBytes < privateBytes / 2
                ? "process-private memory is substantially larger than the managed heap; inspect native allocations, handles, or runtime fragmentation"
                : "managed heap growth is not explained by the tracked application buffers; capture a managed heap dump while elevated";

        _logger.LogWarning("[MemoryWatchdog] Initial diagnosis: {LeadingSignal}.", leadingSignal);
    }

    private static int CountHubTcpConnections()
    {
        int count = 0;
        foreach (var connection in IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpConnections())
        {
            if (connection.LocalEndPoint.Port is 5000 or 3333) count++;
        }
        return count;
    }

    private static double ToMiB(long bytes) => bytes / 1024d / 1024d;
}
