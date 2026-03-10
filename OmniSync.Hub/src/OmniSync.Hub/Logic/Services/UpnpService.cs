using System;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Open.Nat;

namespace OmniSync.Hub.Logic.Services
{
    public class UpnpService : IHostedService
    {
        private readonly ILogger<UpnpService> _logger;
        private NatDevice? _device;

        public UpnpService(ILogger<UpnpService> logger)
        {
            _logger = logger;
        }

        public async Task StartAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation("UPnP Service starting...");
            try
            {
                var discoverer = new NatDiscoverer();
                using var cts = new CancellationTokenSource(10000); // 10 second timeout for discovery
                
                // Link the host cancellation token if available
                using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(cts.Token, cancellationToken);

                _logger.LogDebug("Searching for UPnP devices...");
                _device = await discoverer.DiscoverDeviceAsync(PortMapper.Upnp, linkedCts);

                if (_device != null)
                {
                    try
                    {
                        var externalIp = await _device.GetExternalIPAsync();
                        _logger.LogInformation($"UPnP Device found: {externalIp}");
                        await SetupForwardingAsync();
                    }
                    catch (Exception ipEx)
                    {
                        _logger.LogWarning($"UPnP Device found but failed to get external IP: {ipEx.Message}");
                        // Continue setup anyway
                        await SetupForwardingAsync();
                    }
                }
                else
                {
                    _logger.LogWarning("No UPnP device found after 10 seconds.");
                }
            }
            catch (OperationCanceledException)
            {
                _logger.LogWarning("UPnP discovery timed out or was cancelled.");
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error during UPnP discovery or setup.");
            }
        }

        private async Task SetupForwardingAsync()
        {
            if (_device == null)
            {
                _logger.LogWarning("Cannot setup port forwarding: No UPnP device.");
                return;
            }

            int[] ports = { 3333, 5555 };

            foreach (var port in ports)
            {
                try
                {
                    _logger.LogInformation($"Setting up UPnP port forwarding for port {port}...");
                    var mapping = new Mapping(Protocol.Tcp, port, port, $"OmniHub-{port}");
                    await _device.CreatePortMapAsync(mapping);
                    _logger.LogInformation($"UPnP port forwarding successful for port {port}.");
                }
                catch (Exception ex)
                {
                    _logger.LogWarning($"Failed to setup UPnP for port {port}: {ex.Message}");
                }
            }
        }

        public async Task StopAsync(CancellationToken cancellationToken)
        {
            if (_device == null) return;

            _logger.LogInformation("UPnP Service stopping, removing mappings...");
            int[] ports = { 3333, 5555 };

            foreach (var port in ports)
            {
                try
                {
                    _logger.LogInformation($"Removing UPnP port forwarding for port {port}...");
                    var mapping = new Mapping(Protocol.Tcp, port, port, $"OmniHub-{port}");
                    await _device.DeletePortMapAsync(mapping);
                    _logger.LogInformation($"UPnP port mapping removed for port {port}.");
                }
                catch (Exception ex)
                {
                    _logger.LogWarning($"Failed to remove UPnP mapping for port {port}: {ex.Message}");
                }
            }
        }
    }
}
