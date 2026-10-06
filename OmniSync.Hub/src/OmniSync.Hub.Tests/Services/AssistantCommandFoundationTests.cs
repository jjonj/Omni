using System.Net.Http;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using OmniSync.Hub.Logic.Services.AssistantCommands;

namespace OmniSync.Hub.Tests.Services;

public sealed class AssistantCommandFoundationTests
{
    [Fact]
    public void RelativeTimingSuffixIsSeparatedFromCommand()
    {
        var now = new DateTimeOffset(2026, 10, 6, 19, 0, 0, TimeSpan.FromHours(2));

        var parsed = ExecutionModifierParser.Parse(
            "play the Tetris movie in one hour",
            now);

        Assert.Equal("play the Tetris movie", parsed.CommandText);
        Assert.Equal(now.AddHours(1), parsed.RunAt);
        Assert.Equal("in one hour", parsed.ModifierText);
    }

    [Fact]
    public void HalfHourTimingSuffixIsSupported()
    {
        var now = DateTimeOffset.Parse("2026-10-06T19:00:00+02:00");

        var parsed = ExecutionModifierParser.Parse(
            "play movie Tetris in half an hour",
            now);

        Assert.Equal("play movie Tetris", parsed.CommandText);
        Assert.Equal(now.AddMinutes(30), parsed.RunAt);
    }

    [Theory]
    [InlineData("play the Tetris movie", "Tetris")]
    [InlineData("play movie Tetris", "Tetris")]
    [InlineData("watch Dune film", "Dune")]
    [InlineData("open the Alien movie", "Alien")]
    public void MovieCommandExtractsTitle(string text, string expectedTitle)
    {
        var command = CreateMovieCommand();

        var matched = command.TryParse(text, out var arguments);

        Assert.True(matched);
        Assert.Equal(expectedTitle, arguments["title"]);
    }

    [Fact]
    public void MovieCommandSkipsStartupBacklog()
    {
        var command = CreateMovieCommand();

        Assert.False(command.Descriptor.QueuePolicy.RunFromBacklog);
        Assert.Equal(
            AssistantExecutionHost.Desktop,
            command.Descriptor.Host);
    }

    [Fact]
    public void RegistryFindsCommandsWithoutCentralSwitch()
    {
        var command = CreateMovieCommand();
        var registry = new AssistantCommandRegistry(
            new IAssistantCommand[] { command });

        var match = registry.TryResolve("play the Tetris movie");

        Assert.NotNull(match);
        Assert.Equal("media.play_movie", match.Command.Descriptor.Id);
        Assert.Equal("Tetris", match.Arguments["title"]);
    }

    private static StremioMovieCommand CreateMovieCommand()
        => new(
            Mock.Of<IHttpClientFactory>(),
            NullLogger<StremioMovieCommand>.Instance);
}
