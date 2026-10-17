using OmniSync.Hub.Presentation.Controllers;

namespace OmniSync.Hub.Tests.Presentation;

public class AndroidUpdateRustDelegatePolicyTests
{
    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("http://127.0.0.1:5001")]
    [InlineData("http://[::1]:5001/")]
    public void LocalOnlyParallelHubIsAccepted(string? configured)
    {
        var uri = AndroidUpdateRustDelegatePolicy.ResolveLoopbackBaseUrl(configured);
        Assert.NotNull(uri);
        Assert.Equal(5001, uri.Port);
        Assert.Equal("/", uri.AbsolutePath);
    }

    [Theory]
    [InlineData("https://127.0.0.1:5001/")]
    [InlineData("http://127.0.0.1:5000/")]
    [InlineData("http://127.0.0.1:3334/")]
    [InlineData("http://192.168.1.37:5001/")]
    [InlineData("http://localhost:5001/")]
    [InlineData("http://user:password@127.0.0.1:5001/")]
    [InlineData("http://127.0.0.1:5001/anything")]
    [InlineData("http://127.0.0.1:5001/?ticket=secret")]
    [InlineData("not a url")]
    public void RemoteHostsAlternatePortsAndCredentialsAreRejected(string configured)
    {
        Assert.Null(AndroidUpdateRustDelegatePolicy.ResolveLoopbackBaseUrl(configured));
    }
}
