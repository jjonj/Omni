using Moq;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Services;
using Xunit;
using System.Text.Json;

namespace OmniSync.Hub.Tests.Services
{
    public class ResourceOpenerServiceTests
    {
        private readonly Mock<ProcessService> _processServiceMock;
        private readonly Mock<HubSettingsService> _settingsServiceMock;
        private readonly ResourceOpenerService _service;

        public ResourceOpenerServiceTests()
        {
            var loggerFactory = new Mock<Microsoft.Extensions.Logging.ILoggerFactory>();
            var hubMonitorLogger = new Mock<Microsoft.Extensions.Logging.ILogger<Logic.Monitoring.HubMonitorService>>();
            var appLifetimeMock = new Mock<Microsoft.Extensions.Hosting.IHostApplicationLifetime>();
            
            var monitorMock = new Mock<Logic.Monitoring.HubMonitorService>(
                appLifetimeMock.Object,
                hubMonitorLogger.Object
            );

            _processServiceMock = new Mock<ProcessService>(
                new Mock<HubSettingsService>(new Mock<Microsoft.Extensions.Logging.ILogger<HubSettingsService>>().Object).Object,
                monitorMock.Object
            );
            _settingsServiceMock = new Mock<HubSettingsService>(new Mock<Microsoft.Extensions.Logging.ILogger<HubSettingsService>>().Object);
            
            _service = new ResourceOpenerService(_processServiceMock.Object, _settingsServiceMock.Object);
        }

        [Fact]
        public async Task OpenResource_ShouldCallShellExecute_ForNormalFile()
        {
            // Arrange
            string path = @"D:\\test.png";

            // Act
            await _service.OpenResource(path);

            // Assert
            _processServiceMock.Verify(p => p.ShellExecute(path), Times.Once);
        }

        [Fact]
        public async Task OpenResource_ShouldUseChromeMapping_ForRemoteUrls()
        {
            // Arrange
            string url = "https://google.com";
            _settingsServiceMock.Setup(s => s.GetPath("chrome")).Returns(@"C:\\Chrome\\chrome.exe");

            // Act
            await _service.OpenResource(url);

            // Assert
            _processServiceMock.Verify(p => p.ExecuteCommand(It.Is<string>(s => 
                s.Contains("chrome.exe") && 
                s.Contains(url))), Times.Once);
        }

        [Fact]
        public async Task OpenResource_ShouldUseNotepadPlusPlus_WithLineNumber()
        {
            // Arrange
            string path = @"D:\\code.cs";
            int lineNumber = 42;

            // Act
            await _service.OpenResource(path, lineNumber);

            // Assert
            _processServiceMock.Verify(p => p.ExecuteCommand(It.Is<string>(s => 
                s.Contains("notepad++.exe") && 
                s.Contains("-n42") && 
                s.Contains(path))), Times.Once);
        }

        [Fact]
        public async Task OpenResource_ShouldUseChromeMapping_ForHtmlFiles()
        {
            // Arrange
            string path = @"D:\\test.html";
            _settingsServiceMock.Setup(s => s.GetPath("chrome")).Returns(@"C:\\Chrome\\chrome.exe");

            // Act
            await _service.OpenResource(path);

            // Assert
            _processServiceMock.Verify(p => p.ExecuteCommand(It.Is<string>(s => 
                s.Contains("chrome.exe") && 
                s.Contains(path))), Times.Once);
        }

        [Fact]
        public async Task OpenResource_ShouldUseNotepadPlusPlus_ForCodeFileWithoutLineNumber()
        {
            // Arrange
            string path = @"D:\\code.cs";

            // Act
            await _service.OpenResource(path);

            // Assert
            _processServiceMock.Verify(p => p.ExecuteCommand(It.Is<string>(s => 
                s.Contains("notepad++.exe") && 
                s.Contains(path))), Times.Once);
        }
    }
}
