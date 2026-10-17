using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Moq;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Logic.Services;
using Xunit;

namespace OmniSync.Hub.Tests.Services;

public class VoiceSceneMacroTests
{
    [Theory]
    [InlineData("morning")]
    [InlineData("idle")]
    [InlineData("music")]
    [InlineData("pause")]
    [InlineData("dictate")]
    public async Task FixedSceneMacro_ForwardsToVoiceSceneService(string scene)
    {
        var monitor = new Mock<HubMonitorService>(
            Mock.Of<IHostApplicationLifetime>(),
            Mock.Of<ILogger<HubMonitorService>>());
        var settings = new Mock<HubSettingsService>(Mock.Of<ILogger<HubSettingsService>>(), null!);
        var process = new Mock<ProcessService>(settings.Object, monitor.Object);
        var keyboard = new Mock<KeyboardHook>(Mock.Of<ILogger<KeyboardHook>>());
        var input = new Mock<InputService>(Mock.Of<ILogger<InputService>>(), keyboard.Object);
        var clipboard = new Mock<ClipboardService>();
        var scenes = new Mock<IVoiceSceneService>();

        var macros = new MacroService(process.Object, input.Object, clipboard.Object, monitor.Object, scenes.Object);
        await macros.ExecuteMacroAsync("scene " + scene);

        scenes.Verify(s => s.ExecuteAsync(scene), Times.Once);
    }

    [Theory]
    [InlineData("https://music.youtube.com/", true)]
    [InlineData("https://example.org/my-music", false)]
    public void MusicTarget_MigratesOnlyOriginalDefault(string previousTarget, bool shouldMigrate)
    {
        var folder = Path.Combine(Path.GetTempPath(), "OmniMusicSceneTests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        var settingsPath = Path.Combine(folder, "settings.json");
        try
        {
            File.WriteAllText(settingsPath, "{\"MusicSceneLaunchTarget\":\"" + previousTarget + "\"}");
            var config = new HubSettingsService(Mock.Of<ILogger<HubSettingsService>>(), settingsPath);
            Assert.Equal(shouldMigrate, config.Settings.MusicSceneLaunchTarget.Contains("list=PLDfKAXSi6kUZnATwAUfN6tg1dULU-7XcD"));
            if (!shouldMigrate) Assert.Equal(previousTarget, config.Settings.MusicSceneLaunchTarget);
        }
        finally
        {
            Directory.Delete(folder, recursive: true);
        }
    }

    [Fact]
    public void FreshSettings_RegisterAllScenes_WithoutDuplicates()
    {
        var folder = Path.Combine(Path.GetTempPath(), "OmniVoiceSceneTests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        var settingsPath = Path.Combine(folder, "settings.json");
        try
        {
            var config = new HubSettingsService(Mock.Of<ILogger<HubSettingsService>>(), settingsPath);
            var expected = new[] { "Morning", "Idle", "Music", "Pause", "Dictate", "Sleep", "Shutdown" };
            foreach (var name in expected)
            {
                Assert.Single(config.Settings.Macros, m =>
                    string.Equals(m.Category, "Voice", StringComparison.OrdinalIgnoreCase)
                    && string.Equals(m.Name, name, StringComparison.OrdinalIgnoreCase));
            }
        }
        finally
        {
            Directory.Delete(folder, recursive: true);
        }
    }
}
