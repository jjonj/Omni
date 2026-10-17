using System.Net.Http;
using System.IO;
using System.Text.Json;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using OmniSync.Hub.Logic.Services.AssistantCommands;

namespace OmniSync.Hub.Tests.Services;

public sealed class AssistantCommandFoundationTests
{
    public static IEnumerable<object[]> ModifierVectors()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "Shared", "assistant", "modifier_vectors.json");
        var json = File.ReadAllText(path);
        using var document = JsonDocument.Parse(json);
        foreach (var item in document.RootElement.EnumerateArray())
        {
            yield return new object[]
            {
                item.GetProperty("text").GetString()!,
                item.GetProperty("command").GetString()!,
                item.GetProperty("delaySeconds").ValueKind == JsonValueKind.Null
                    ? (double?)null
                    : item.GetProperty("delaySeconds").GetDouble()
            };
        }
    }

    [Theory]
    [MemberData(nameof(ModifierVectors))]
    public void SharedModifierVectorsMatch(
        string text,
        string expectedCommand,
        double? delaySeconds)
    {
        var now = DateTimeOffset.Parse("2026-10-06T19:00:00+02:00");
        var parsed = ExecutionModifierParser.Parse(text, now);

        Assert.Equal(expectedCommand, parsed.CommandText);
        Assert.Equal(
            delaySeconds is null ? null : now.AddSeconds(delaySeconds.Value),
            parsed.RunAt);
    }

    [Theory]
    [InlineData("play the Tetris movie", "Tetris")]
    [InlineData("play movie Tetris", "Tetris")]
    [InlineData("watch Dune film", "Dune")]
    [InlineData("open the Alien movie", "Alien")]
    [InlineData("play movie Gone in 60 Seconds", "Gone in 60 Seconds")]
    public void MovieCommandExtractsTitle(string text, string expectedTitle)
    {
        var command = CreateMovieCommand();
        var parsed = command.TryParse(new AssistantParseInput(text));

        Assert.NotNull(parsed);
        Assert.Equal(expectedTitle, parsed!.Arguments["title"].GetString());
    }

    [Fact]
    public void FullMovieReadingBeatsModifierCollisionReading()
    {
        var command = CreateMovieCommand();
        var full = command.TryParse(new AssistantParseInput("play movie Gone in 60 Seconds"));
        var stripped = command.TryParse(new AssistantParseInput("play movie Gone"));

        Assert.NotNull(full);
        Assert.NotNull(stripped);
        Assert.True(full!.Specificity > stripped!.Specificity);
    }

    [Fact]
    public void MovieSelectionHonorsYearHint()
    {
        var candidates = new[]
        {
            new MovieCandidate("tt1160419", "Dune", "2021", 1),
            new MovieCandidate("tt0087182", "Dune", "1984", 2)
        };

        var selection = StremioMovieCommand.SelectMovie("Dune 1984", candidates);

        Assert.Equal("tt0087182", selection.Candidate?.Id);
        Assert.False(selection.IsClosestMatch);
    }

    [Fact]
    public void WeakMovieSelectionRequiresClarification()
    {
        var candidates = new[]
        {
            new MovieCandidate("tt0000001", "Completely Different", "2023", 1),
            new MovieCandidate("tt0000002", "Another Film", "2022", 2)
        };

        var selection = StremioMovieCommand.SelectMovie("Tetris", candidates);

        Assert.Null(selection.Candidate);
        Assert.NotEmpty(selection.Choices);
    }

    [Fact]
    public void ReflectionProviderDiscoversValidCommandModules()
    {
        using var services = new ServiceCollection()
            .AddLogging()
            .AddHttpClient()
            .BuildServiceProvider();
        var provider = new ReflectionCommandProvider(
            services,
            NullLogger<ReflectionCommandProvider>.Instance);
        var catalog = new AssistantCommandCatalog(
            new IAssistantCommandProvider[] { provider },
            NullLogger<AssistantCommandCatalog>.Instance);

        Assert.Contains(catalog.Descriptors, descriptor => descriptor.Id == "media.play_movie");
        Assert.Contains(catalog.Descriptors, descriptor => descriptor.Id == "note.add");
        Assert.DoesNotContain(catalog.Rejected, rejection => rejection.CommandId == "media.play_movie");
    }

    [Fact]
    public void AllDiscoveredExamplesParseBackToTheirCommand()
    {
        using var services = new ServiceCollection()
            .AddLogging()
            .AddHttpClient()
            .BuildServiceProvider();
        var provider = new ReflectionCommandProvider(
            services,
            NullLogger<ReflectionCommandProvider>.Instance);
        var catalog = new AssistantCommandCatalog(
            new IAssistantCommandProvider[] { provider },
            NullLogger<AssistantCommandCatalog>.Instance);

        foreach (var descriptor in catalog.Descriptors)
        {
            Assert.True(catalog.TryGet(descriptor.Id, out var command));
            foreach (var example in descriptor.Examples)
            {
                var parsed = command.TryParse(new AssistantParseInput(example.Utterance));
                Assert.NotNull(parsed);
                foreach (var expected in example.Arguments)
                {
                    Assert.True(parsed!.Arguments.TryGetValue(expected.Key, out var actual));
                    Assert.Equal(expected.Value, actual.GetString());
                }

                var route = catalog.Resolve(example.Utterance);
                var match = Assert.IsType<AssistantRouteResult.Matched>(route);
                Assert.Equal(descriptor.Id, match.Match.Command.Descriptor.Id);
            }
        }
    }

    [Fact]
    public void ThrowingParserIsQuarantinedWithoutBreakingOtherCommands()
    {
        var good = CreateMovieCommand();
        var bad = new ThrowingCommand();
        var provider = new StaticProvider(bad, good);
        var catalog = new AssistantCommandCatalog(
            new[] { provider },
            NullLogger<AssistantCommandCatalog>.Instance);

        var route = catalog.Resolve("play the Tetris movie");

        var match = Assert.IsType<AssistantRouteResult.Matched>(route);
        Assert.Equal("media.play_movie", match.Match.Command.Descriptor.Id);
        Assert.Contains(catalog.Rejected, rejection => rejection.CommandId == "test.throwing");
    }

    [Fact]
    public void CorruptRunStoreIsMovedAside()
    {
        var directory = Path.Combine(Path.GetTempPath(), "OmniAssistantTests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "runs.json");
        File.WriteAllText(path, "{ definitely not json");

        var configuration = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["Assistant:RunStorePath"] = path
            })
            .Build();

        var store = new AssistantRunStore(
            NullLogger<AssistantRunStore>.Instance,
            configuration);

        Assert.Empty(store.GetAll());
        Assert.False(File.Exists(path));
        Assert.Single(Directory.GetFiles(directory, "runs.json.corrupt-*"));
    }

    [Fact]
    public void ConnectedDeviceCommandIsDiscoverableAndSelectsAndroidHost()
    {
        var devices = new AssistantDeviceRegistry();
        devices.Advertise(
            "phone-1",
            "connection-1",
            new[]
            {
                new DeviceAssistantCommandDescriptor(
                    "wake.pc",
                    1,
                    "Wake PC",
                    new List<string>
                    {
                        @"^(?:turn on|wake) (?:my )?pc$"
                    },
                    new List<DeviceAssistantArgument>(),
                    new List<DeviceAssistantExample>
                    {
                        new("turn on my pc", new Dictionary<string, string>())
                    },
                    AcceptsRelativeTime: true,
                    MaxDelayMillis: 15 * 60 * 1000,
                    Risk: "Low",
                    MinTrustWithoutConfirmation: "Low",
                    Idempotent: true,
                    TimeoutSeconds: 15,
                    ConcurrencyKey: "wake.pc")
            });

        var provider = new DeviceCommandProvider(devices);
        var catalog = new AssistantCommandCatalog(
            new IAssistantCommandProvider[] { provider },
            NullLogger<AssistantCommandCatalog>.Instance);

        var route = catalog.Resolve("turn on my pc");
        var match = Assert.IsType<AssistantRouteResult.Matched>(route);
        Assert.Equal("wake.pc", match.Match.Command.Descriptor.Id);

        var selector = new AssistantHostSelector(devices);
        Assert.Equal("android:phone-1", selector.SelectHost(match.Match.Command));
    }

    [Fact]
    public void DisconnectedDeviceCommandDisappearsFromCatalogue()
    {
        var devices = new AssistantDeviceRegistry();
        devices.Advertise(
            "phone-1",
            "connection-1",
            Array.Empty<DeviceAssistantCommandDescriptor>());
        devices.Disconnect("connection-1");

        Assert.Empty(devices.GetConnected());
    }

    private static StremioMovieCommand CreateMovieCommand()
        => new(
            Mock.Of<IHttpClientFactory>(),
            NullLogger<StremioMovieCommand>.Instance);

    private sealed class StaticProvider : IAssistantCommandProvider
    {
        private readonly IReadOnlyList<IAssistantCommand> _commands;

        public StaticProvider(params IAssistantCommand[] commands)
        {
            _commands = commands;
        }

        public string Name => "test";
        public IReadOnlyList<AssistantCommandRejection> Rejected => Array.Empty<AssistantCommandRejection>();
        public IReadOnlyList<IAssistantCommand> GetCommands() => _commands;
    }

    private sealed class ThrowingCommand : IAssistantCommand
    {
        public AssistantCommandDescriptor Descriptor { get; } = new(
            "test.throwing",
            1,
            "Throws while parsing.",
            AssistantExecutionHost.Desktop,
            Array.Empty<AssistantArgumentSpec>(),
            Array.Empty<AssistantCommandExample>(),
            AssistantTimingSupport.None,
            AssistantStalenessPolicy.Unlimited,
            new AssistantRiskPolicy(AssistantRiskLevel.Low),
            new AssistantExecutionPolicy(TimeSpan.FromSeconds(1), null, true));

        public AssistantParseResult? TryParse(AssistantParseInput input)
            => throw new InvalidOperationException("boom");

        public Task<AssistantCommandOutcome> ExecuteAsync(
            AssistantCommandInvocation invocation,
            CancellationToken cancellationToken)
            => Task.FromResult<AssistantCommandOutcome>(
                new AssistantCommandOutcome.Succeeded("not reached"));
    }
}
