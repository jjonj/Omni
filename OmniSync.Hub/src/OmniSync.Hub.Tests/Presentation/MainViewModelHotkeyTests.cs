using Moq;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Presentation;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Configuration;
using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using Xunit;

namespace OmniSync.Hub.Tests.Presentation
{
    public class MainViewModelHotkeyTests
    {
        private readonly Mock<HubMonitorService> _mockMonitorService;
        private readonly Mock<InputService> _mockInputService;
        private readonly Mock<ProcessService> _mockProcessService;
        private readonly Mock<ShutdownService> _mockShutdownService;
        private readonly Mock<RegistryService> _mockRegistryService;
        private readonly Mock<HubSettingsService> _mockSettingsService;
        private readonly Mock<KeyboardHook> _mockKeyboardHook;
        private readonly Mock<AiCliService> _mockAiCliService;
        private readonly Mock<LayoutCaptureService> _mockLayoutCaptureService;
        private readonly Mock<ProjectLauncherService> _mockProjectLauncherService;

        public MainViewModelHotkeyTests()
        {
            var mockLifetime = new Mock<IHostApplicationLifetime>();
            var mockMonitorLogger = new Mock<ILogger<HubMonitorService>>();
            _mockMonitorService = new Mock<HubMonitorService>(mockLifetime.Object, mockMonitorLogger.Object) { CallBase = true };
            
            _mockKeyboardHook = new Mock<KeyboardHook>(new Mock<ILogger<KeyboardHook>>().Object);
            
            var mockInputLogger = new Mock<ILogger<InputService>>();
            _mockInputService = new Mock<InputService>(mockInputLogger.Object, _mockKeyboardHook.Object) { CallBase = true };
            
            var mockSettingsLogger = new Mock<ILogger<HubSettingsService>>();
            _mockSettingsService = new Mock<HubSettingsService>(mockSettingsLogger.Object) { CallBase = true };
            
            _mockProcessService = new Mock<ProcessService>(_mockSettingsService.Object, _mockMonitorService.Object) { CallBase = true };
            
            var mockShutdownLogger = new Mock<ILogger<ShutdownService>>();
            var mockAudioService = new Mock<AudioService>(); 
            var mockFileService = new Mock<FileService>();
            _mockShutdownService = new Mock<ShutdownService>(mockShutdownLogger.Object, _mockProcessService.Object, mockAudioService.Object, mockFileService.Object) { CallBase = true };
            
            _mockRegistryService = new Mock<RegistryService>(new Mock<ILogger<RegistryService>>().Object) { CallBase = true };
            
            var mockAiLogger = new Mock<ILogger<AiCliService>>();
            var mockConfig = new Mock<IConfiguration>();
            _mockAiCliService = new Mock<AiCliService>(mockAiLogger.Object, _mockSettingsService.Object, _mockProcessService.Object, _mockMonitorService.Object, mockConfig.Object) { CallBase = true };
            
            _mockLayoutCaptureService = new Mock<LayoutCaptureService>() { CallBase = true };
            _mockProjectLauncherService = new Mock<ProjectLauncherService>(_mockProcessService.Object, _mockSettingsService.Object) { CallBase = true };

            // Setup default settings
            var settings = new HubSettings
            {
                Hotkeys = new List<HotkeyConfig>
                {
                    new HotkeyConfig { Name = "Test Hotkey", Action = "TEST_ACTION", Key = "Ctrl+T", Category = "General" }
                },
                Projects = new List<Project>()
            };
            _mockSettingsService.SetupGet(s => s.Settings).Returns(settings);
        }

        [Fact]
        public void DeleteHotkeyCommand_ShouldRemoveHotkeyByAction()
        {
            // Arrange
            var vm = new MainViewModel(_mockMonitorService.Object, _mockInputService.Object, _mockProcessService.Object, _mockShutdownService.Object, _mockRegistryService.Object, _mockSettingsService.Object, _mockKeyboardHook.Object, _mockAiCliService.Object, _mockLayoutCaptureService.Object, _mockProjectLauncherService.Object);
            
            // Act
            vm.DeleteHotkeyCommand.Execute("TEST_ACTION");

            // Assert
            _mockSettingsService.Verify(s => s.RemoveHotkeyByAction("TEST_ACTION"), Times.Once);
        }

        [Fact]
        public void ChangingHotkeyCategory_ShouldUpdateSettings()
        {
            // Arrange
            var vm = new MainViewModel(_mockMonitorService.Object, _mockInputService.Object, _mockProcessService.Object, _mockShutdownService.Object, _mockRegistryService.Object, _mockSettingsService.Object, _mockKeyboardHook.Object, _mockAiCliService.Object, _mockLayoutCaptureService.Object, _mockProjectLauncherService.Object);
            var hotkey = vm.Hotkeys.First();

            // Act
            hotkey.Category = "New Category";
            
            // Assert
            _mockSettingsService.Verify(s => s.SaveSettings(), Times.AtLeastOnce);
        }
    }
}
