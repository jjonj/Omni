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
                var cts = new CancellationTokenSource(5000); // 5 second timeout for discovery
                _device = await discoverer.DiscoverDeviceAsync(PortMapper.Upnp, cts);

                if (_device != null)
                {
                    _logger.LogInformation($"UPnP Device found: {await _device.GetExternalIPAsync()}");
                    await SetupForwardingAsync();
                }
                else
                {
                    _logger.LogWarning("No UPnP device found.");
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error during UPnP discovery or setup.");
            }
        }

        private async Task SetupForwardingAsync()
        {
            if (_device == null) return;

            int[] ports = { 3333, 5555 };

            foreach (var port in ports)
            {
                try
                {
                    _logger.LogInformation($"Setting up UPnP port forwarding for port {port}...");
                    await _device.CreatePortMapAsync(new Mapping(Protocol.Tcp, port, port, $"OmniHub-{port}"));
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
                    await _device.DeletePortMapAsync(new Mapping(Protocol.Tcp, port, port, $"OmniHub-{port}"));
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
