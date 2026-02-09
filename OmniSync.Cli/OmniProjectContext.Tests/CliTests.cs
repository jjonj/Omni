using Xunit;
using OmniProjectContext;
using System.IO;
using System;

namespace OmniProjectContext.Tests;

public class CliTests
{
    [Fact]
    public void Main_WithNoArgs_ShouldPrintUsage()
    {
        using var sw = new StringWriter();
        Console.SetOut(sw);
        
        Program.Main(Array.Empty<string>());
        
        var result = sw.ToString();
        Assert.Contains("Usage:", result);
    }

    [Fact]
    public void Main_WithSessionCommand_ShouldNotThrow()
    {
        Program.Main(new[] { "session" });
    }

    [Fact]
    public void Main_WithContextCommand_ShouldNotThrow()
    {
        Program.Main(new[] { "context" });
    }

    [Fact]
    public void Main_WithSyncCommand_ShouldNotThrow()
    {
        Program.Main(new[] { "sync" });
    }
}
