using System.IO;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniZones.Runtime;

namespace OmniSync.Hub.Logic.Services
{
    /// <summary>
    /// Hosts OmniZones (drag-to-zone window snapping) inside the Hub. All logic lives in the standalone
    /// OmniZones.Core library; this is only the lifetime glue. Settings: %APPDATA%\OmniZones\settings.json,
    /// shared with the standalone app (only one of the two runs at a time).
    /// </summary>
    public class OmniZonesHostedService : IHostedService
    {
        private readonly ILogger<OmniZonesHostedService> _logger;
        private ZoneRuntime? _runtime;

        /// <summary>For the tray menu.</summary>
        public static OmniZonesHostedService? Instance { get; private set; }

        public OmniZonesHostedService(ILogger<OmniZonesHostedService> logger)
        {
            _logger = logger;
            Instance = this;
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            // Temporary side-by-side Rust OmniZones validation. The marker is
            // intentionally local and reversible; all other C# Hub services
            // remain active while Rust exclusively owns the zone runtime.
            var disabledMarker = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "OmniZones", "hub-disabled.flag");
            if (File.Exists(disabledMarker))
            {
                _logger.LogInformation("OmniZones hosted runtime disabled for Rust validation.");
                return Task.CompletedTask;
            }
            try
            {
                _runtime = ZoneRuntime.StartOnDedicatedThread();
                if (_runtime == null) { _logger.LogWarning("OmniZones is already running (standalone copy?). Hub copy not started."); return Task.CompletedTask; }
                _runtime.Activity += m => _logger.LogInformation("OmniZones: {Message}", m);
                _logger.LogInformation("OmniZones started.");
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "OmniZones failed to start.");
            }
            return Task.CompletedTask;
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            try { _runtime?.Stop(); } catch (Exception ex) { _logger.LogError(ex, "OmniZones failed to stop cleanly."); }
            _runtime = null;
            return Task.CompletedTask;
        }

        public void OpenSettings() => _runtime?.OpenSettings();
        public void OpenEditor() => _runtime?.OpenEditor();
        public SavedZoneAssignment? CaptureAssignment(IntPtr hwnd) => _runtime?.Dispatcher.Invoke(() => _runtime.Manager.CaptureAssignment(hwnd));
        public bool RestoreAssignment(IntPtr hwnd, string layoutId, string zoneId, string monitorDevice)
        {
            var monitor = OmniZones.Native.WindowTools.Monitors().FirstOrDefault(m => m.Device == monitorDevice);
            return _runtime != null && monitor != null && _runtime.Dispatcher.Invoke(() => _runtime.Manager.RestoreAssignment(hwnd, layoutId, zoneId, monitor));
        }
    }
}
