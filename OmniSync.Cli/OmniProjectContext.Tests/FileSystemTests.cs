using Xunit;
using OmniProjectContext.Services;
using System.IO;
using System.Collections.Generic;
using System.Linq;

namespace OmniProjectContext.Tests;

public class FileSystemTests
{
    [Fact]
    public void ShouldIgnore_ReturnsTrue_ForExcludedFolders()
    {
        var service = new FileSystemService();
        
        Assert.True(service.ShouldIgnoreFolder("conductor"));
        Assert.True(service.ShouldIgnoreFolder("Assets"));
        Assert.True(service.ShouldIgnoreFolder("node_modules"));
        Assert.True(service.ShouldIgnoreFolder(".git"));
        Assert.True(service.ShouldIgnoreFolder("bin"));
        Assert.True(service.ShouldIgnoreFolder("obj"));
    }

    [Fact]
    public void ShouldIgnore_ReturnsFalse_ForRegularFolders()
    {
        var service = new FileSystemService();
        
        Assert.False(service.ShouldIgnoreFolder("src"));
        Assert.False(service.ShouldIgnoreFolder("OmniSync.Hub"));
        Assert.False(service.ShouldIgnoreFolder("Logic"));
    }

    [Fact]
    public void ShouldIgnore_ReturnsFalse_ForOmniFolder()
    {
        var service = new FileSystemService();
        
        Assert.False(service.ShouldIgnoreFolder(".omni"));
    }
}
