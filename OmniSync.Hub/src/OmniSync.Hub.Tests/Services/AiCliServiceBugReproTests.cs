using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Configuration;
using Moq;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Logic.Services;
using Microsoft.Extensions.Hosting;
using System.Diagnostics;
using System.Management;
using Xunit;
using System.Threading.Tasks;

namespace OmniSync.Hub.Tests.Services
{
    public class AiCliServiceBugReproTests
    {
        [Fact]
        public async Task DiscoverSessionsAsync_ShouldNotDisposeNewSession_WhenPhantomProcessExists()
        {
            // This test aims to reproduce the bug where a newly launched session is disposed
            // because WMI discovery incorrectly identifies it as a wrapper.
            
            var loggerMock = new Mock<ILogger<AiCliService>>();
            var settingsMock = new Mock<HubSettingsService>(new Mock<ILogger<HubSettingsService>>().Object);
            var monitorMock = new Mock<HubMonitorService>(new Mock<IHostApplicationLifetime>().Object, new Mock<ILogger<HubMonitorService>>().Object);
            var processServiceMock = new Mock<ProcessService>(settingsMock.Object, monitorMock.Object);
            var configMock = new Mock<IConfiguration>();

            // Mock settings to return a default object
            settingsMock.Setup(s => s.Settings).Returns(new HubSettings());

            var aiCliService = new AiCliService(loggerMock.Object, settingsMock.Object, processServiceMock.Object, monitorMock.Object, configMock.Object);

            // ... rest of the test logic ...
        }
    }
}
