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
    public class MacroServiceTests
    {
        private readonly Mock<ProcessService> _processServiceMock;
        private readonly Mock<InputService> _inputServiceMock;
        private readonly Mock<ClipboardService> _clipboardServiceMock;
        private readonly Mock<HubMonitorService> _monitorServiceMock;
        private readonly MacroService _service;

        public MacroServiceTests()
        {
            var hubMonitorLogger = new Mock<ILogger<HubMonitorService>>();
            var appLifetimeMock = new Mock<IHostApplicationLifetime>();
            _monitorServiceMock = new Mock<HubMonitorService>(appLifetimeMock.Object, hubMonitorLogger.Object);

            var settingsLoggerMock = new Mock<ILogger<HubSettingsService>>();
            var settingsServiceMock = new Mock<HubSettingsService>(settingsLoggerMock.Object);
            _processServiceMock = new Mock<ProcessService>(settingsServiceMock.Object, _monitorServiceMock.Object);
            
            var keyboardHookLoggerMock = new Mock<ILogger<KeyboardHook>>();
            var keyboardHookMock = new Mock<KeyboardHook>(keyboardHookLoggerMock.Object);
            _inputServiceMock = new Mock<InputService>(new Mock<ILogger<InputService>>().Object, keyboardHookMock.Object);
            _clipboardServiceMock = new Mock<ClipboardService>();

            _service = new MacroService(
                _processServiceMock.Object,
                _inputServiceMock.Object,
                _clipboardServiceMock.Object,
                _monitorServiceMock.Object);
        }

        [Fact]
        public async Task ExecuteMacroAsync_ShouldSendKeys()
        {
            // Arrange
            var json = "[{\"type\": \"send\", \"keys\": \"hello\"}]";
            var commands = JsonDocument.Parse(json).RootElement;

            // Act
            await _service.ExecuteMacroAsync(commands);

            // Assert
            _inputServiceMock.Verify(i => i.SendKeys("hello"), Times.Once);
        }

        [Fact]
        public async Task ExecuteMacroAsync_ShouldSleep()
        {
            // Arrange
            var json = "[{\"type\": \"sleep\", \"durationMs\": 10}]";
            var commands = JsonDocument.Parse(json).RootElement;

            // Act
            var startTime = System.DateTime.Now;
            await _service.ExecuteMacroAsync(commands);
            var duration = System.DateTime.Now - startTime;

            // Assert
            Assert.True(duration.TotalMilliseconds >= 10);
        }

        [Fact]
        public async Task ExecuteMacroAsync_ShouldRunCommand()
        {
            // Arrange
            var json = "[{\"type\": \"run\", \"path\": \"notepad.exe\"}]";
            var commands = JsonDocument.Parse(json).RootElement;

            // Act
            await _service.ExecuteMacroAsync(commands);

            // Assert
            _processServiceMock.Verify(p => p.ExecuteCommand("notepad.exe"), Times.Once);
        }

        [Fact]
        public async Task ExecuteMacroAsync_ShouldSetClipboard()
        {
            // Arrange
            var json = "[{\"type\": \"clipboard\", \"text\": \"test content\"}]";
            var commands = JsonDocument.Parse(json).RootElement;

            // Act
            await _service.ExecuteMacroAsync(commands);

            // Assert
            _clipboardServiceMock.Verify(c => c.SetClipboardText("test content"), Times.Once);
        }

        [Fact]
        public async Task ExecuteMacroAsync_ShouldExpandVariables()
        {
            // Arrange
            _inputServiceMock.Setup(i => i.GetActiveWindowTitle()).Returns("My Window");
            var json = "[{\"type\": \"send\", \"keys\": \"Title: {ACTIVE_WINDOW_TITLE}\"}]";
            var commands = JsonDocument.Parse(json).RootElement;

            // Act
            await _service.ExecuteMacroAsync(commands);

            // Assert
            _inputServiceMock.Verify(i => i.SendKeys("Title: My Window"), Times.Once);
        }
    }
}
