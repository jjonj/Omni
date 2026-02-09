using Xunit;
using OmniProjectContext.Services;
using System.IO;

namespace OmniProjectContext.Tests;

public class StateTests
{
    [Fact]
    public void Initialize_ShouldCreateStateDirectory()
    {
        string tempPath = Path.Combine(Path.GetTempPath(), Path.GetRandomFileName());
        var service = new StateService(tempPath);
        
        service.Initialize();
        
        Assert.True(Directory.Exists(Path.Combine(tempPath, ".omni", "projectcontext")));
    }
}
