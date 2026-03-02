using Moq;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Logic.Monitoring;
using Xunit;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Hosting;

namespace OmniSync.Hub.Tests.Services
{
    public class ResourceOpenerServiceTests
    {
        private readonly Mock<ProcessService> _processServiceMock;
        private readonly Mock<HubSettingsService> _settingsServiceMock;
        private readonly ResourceOpenerService _service;

        public ResourceOpenerServiceTests()
        {
            var hubMonitorLogger = new Mock<ILogger<HubMonitorService>>();
            var appLifetimeMock = new Mock<IHostApplicationLifetime>();
            
            var monitorMock = new Mock<HubMonitorService>(
                appLifetimeMock.Object,
                hubMonitorLogger.Object
            );

            var settingsLoggerMock = new Mock<ILogger<HubSettingsService>>();
            _settingsServiceMock = new Mock<HubSettingsService>(settingsLoggerMock.Object);
            
            _processServiceMock = new Mock<ProcessService>(
                _settingsServiceMock.Object,
                monitorMock.Object
            );
            
            _service = new ResourceOpenerService(_processServiceMock.Object, _settingsServiceMock.Object, monitorMock.Object);
        }

        [Fact]
        public async Task OpenResource_ShouldCallShellExecute_ForNormalFile()
        {
            // Arrange
            string path = @"D:\\test.png";

            // Act
            await _service.OpenResource(path);

            // Assert
            _processServiceMock.Verify(p => p.ShellExecute(path, null, null), Times.Once);
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
            _processServiceMock.Verify(p => p.ShellExecute(It.Is<string>(s => s.Contains("chrome.exe")), It.Is<string>(s => s.Contains(url)), null), Times.Once);
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
            _processServiceMock.Verify(p => p.ShellExecute(It.Is<string>(s => s.Contains("notepad++.exe")), It.Is<string>(s => s.Contains("-n42") && s.Contains(path)), null), Times.Once);
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
            _processServiceMock.Verify(p => p.ShellExecute(It.Is<string>(s => s.Contains("chrome.exe")), It.Is<string>(s => s.Contains(path)), null), Times.Once);
        }

        [Fact]
        public async Task OpenResource_ShouldUseNotepadPlusPlus_ForCodeFileWithoutLineNumber()
        {
            // Arrange
            string path = @"D:\\code.cs";

            // Act
            await _service.OpenResource(path);

            // Assert
            _processServiceMock.Verify(p => p.ShellExecute(It.Is<string>(s => s.Contains("notepad++.exe")), It.Is<string>(s => s.Contains(path)), null), Times.Once);
        }
    }
}
