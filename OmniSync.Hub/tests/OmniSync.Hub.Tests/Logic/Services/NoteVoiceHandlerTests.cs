using System.IO;
using OmniSync.Hub.Logic.Services.VoiceInbox;
using Xunit;

namespace OmniSync.Hub.Tests.Logic.Services;

public class NoteVoiceHandlerTests : IDisposable
{
    private readonly string _vault = Path.Combine(Path.GetTempPath(), "omni-vault-" + Guid.NewGuid().ToString("N"));

    public NoteVoiceHandlerTests()
    {
        Directory.CreateDirectory(Path.Combine(_vault, "Wartribes"));
        Directory.CreateDirectory(Path.Combine(_vault, "Projects And Work", "Omni"));
    }

    public void Dispose() => Directory.Delete(_vault, recursive: true);

    private static VoiceInboxItem Item(string text) => new(text, "test", null, new DateTimeOffset(2026, 10, 3, 15, 0, 0, TimeSpan.Zero));

    [Theory]
    [InlineData("wartribes note implement dagger attacks", "Wartribes", "Implement dagger attacks")]
    [InlineData("add a wartribes note that we need to implement dagger attacks", "Wartribes", "We need to implement dagger attacks")]
    [InlineData("Wartribes note: implement dagger attacks", "Wartribes", "Implement dagger attacks")]
    [InlineData("omni note build sleep into the hub", "Projects And Work/Omni", "Build sleep into the hub")]
    [InlineData("note buy milk", "", "Buy milk")]
    public async Task AppendsToProjectInbox(string spoken, string folder, string expected)
    {
        var handler = new NoteVoiceHandler(_vault);

        var result = await handler.TryHandleAsync(Item(spoken), CancellationToken.None);

        Assert.NotNull(result);
        var file = Path.Combine(_vault, folder, NoteVoiceHandler.InboxFileName);
        Assert.Contains($"- [ ] {expected} ", File.ReadAllText(file));
    }

    [Fact]
    public async Task UnknownProjectGoesToVaultRootWithLabel()
    {
        var handler = new NoteVoiceHandler(_vault);

        await handler.TryHandleAsync(Item("garden note plant tulips"), CancellationToken.None);

        Assert.Contains("- [ ] **garden**: Plant tulips", File.ReadAllText(Path.Combine(_vault, NoteVoiceHandler.InboxFileName)));
    }

    [Theory]
    [InlineData("test message")]
    [InlineData("open my notebook")]
    [InlineData("turn off the lights")]
    public async Task IgnoresNonNotes(string spoken)
    {
        var handler = new NoteVoiceHandler(_vault);

        Assert.Null(await handler.TryHandleAsync(Item(spoken), CancellationToken.None));
    }
}
