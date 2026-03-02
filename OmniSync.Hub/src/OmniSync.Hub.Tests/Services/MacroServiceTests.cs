using Moq;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Logic.Monitoring;
using Xunit;
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
            var script = "send hello";

            // Act
            await _service.ExecuteMacroAsync(script);

            // Assert
            _inputServiceMock.Verify(i => i.SendKeys("hello"), Times.Once);
        }

        [Fact]
        public async Task ExecuteMacroAsync_ShouldSleep()
        {
            // Arrange
            var script = "sleep 10";

            // Act
            var startTime = System.DateTime.Now;
            await _service.ExecuteMacroAsync(script);
            var duration = System.DateTime.Now - startTime;

            // Assert
            Assert.True(duration.TotalMilliseconds >= 10);
        }

        [Fact]
        public async Task ExecuteMacroAsync_ShouldRunCommand()
        {
            // Arrange
            var script = "run notepad.exe";

            // Act
            await _service.ExecuteMacroAsync(script);

            // Assert
            _processServiceMock.Verify(p => p.ShellExecute("notepad.exe", "", null), Times.Once);
        }

        [Fact]
        public async Task ExecuteMacroAsync_ShouldSetClipboard()
        {
            // Arrange
            var script = "clipboard test content";

            // Act
            await _service.ExecuteMacroAsync(script);

            // Assert
            _clipboardServiceMock.Verify(c => c.SetClipboardText("test content"), Times.Once);
        }

        [Fact]
        public async Task ExecuteMacroAsync_ShouldExpandVariables()
        {
            // Arrange
            _inputServiceMock.Setup(i => i.GetActiveWindowTitle()).Returns("My Window");
            var script = "send Title: {ACTIVE_WINDOW_TITLE}";

            // Act
            await _service.ExecuteMacroAsync(script);

            // Assert
            _inputServiceMock.Verify(i => i.SendKeys("Title: My Window"), Times.Once);
        }
    }
}
