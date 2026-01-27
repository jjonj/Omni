using Moq;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Hosting;
using Xunit;
using System;

namespace OmniSync.Hub.Tests.Infrastructure
{
    public class ProcessServiceTests
    {
        private readonly Mock<HubSettingsService> _settingsServiceMock;
        private readonly Mock<HubMonitorService> _monitorServiceMock;
        private readonly ProcessService _service;

        public ProcessServiceTests()
        {
            _settingsServiceMock = new Mock<HubSettingsService>(new Mock<ILogger<HubSettingsService>>().Object);
            
            var appLifetimeMock = new Mock<IHostApplicationLifetime>();
            var loggerMock = new Mock<ILogger<HubMonitorService>>();
            _monitorServiceMock = new Mock<HubMonitorService>(appLifetimeMock.Object, loggerMock.Object);

            _service = new ProcessService(_settingsServiceMock.Object, _monitorServiceMock.Object);
        }

        [Fact]
        public void ShellExecute_ShouldNotThrow_WhenPathIsNull()
        {
            // Act & Assert
            var ex = Record.Exception(() => _service.ShellExecute(null, null));
            Assert.Null(ex);
        }

        [Fact]
        public void ShellExecute_ShouldNotThrow_WhenPathIsEmpty()
        {
            // Act & Assert
            var ex = Record.Exception(() => _service.ShellExecute("", ""));
            Assert.Null(ex);
        }
        
        [Fact]
        public void ShellExecute_ShouldLogErrorMessage_WhenProcessStartFails()
        {
            // Act
            _service.ShellExecute("non_existent_file_that_will_fail_to_start_hopefully_123456.xyz", null);

            // Assert
            _monitorServiceMock.Verify(m => m.AddLogMessage(It.Is<string>(s => s.Contains("ShellExecute failed"))), Times.AtLeastOnce());
        }
    }
}
