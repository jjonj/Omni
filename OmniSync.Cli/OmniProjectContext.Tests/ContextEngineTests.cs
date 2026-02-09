using Xunit;
using OmniProjectContext.Services;
using System.IO;
using System;
using System.Collections.Generic;
using System.Linq;

namespace OmniProjectContext.Tests;

public class ContextEngineTests : IDisposable
{
    private readonly string _tempPath;

    public ContextEngineTests()
    {
        _tempPath = Path.Combine(Path.GetTempPath(), "opc-engine-" + Guid.NewGuid().ToString());
        Directory.CreateDirectory(_tempPath);
    }

    [Fact]
    public void GenerateFileTree_ShouldIncludeFilesAndRespectExclusions()
    {
        // Setup dummy structure
        Directory.CreateDirectory(Path.Combine(_tempPath, "src"));
        Directory.CreateDirectory(Path.Combine(_tempPath, "bin")); // Should be ignored
        File.WriteAllText(Path.Combine(_tempPath, "src", "main.cs"), "code");
        File.WriteAllText(Path.Combine(_tempPath, "README.md"), "info");
        File.WriteAllText(Path.Combine(_tempPath, "bin", "app.dll"), "binary");

        var fileSystemService = new FileSystemService();
        var engine = new ContextEngine(_tempPath, fileSystemService);
        
        var files = engine.GenerateFileTree();
        
        Assert.Contains(files, f => f.EndsWith("main.cs"));
        Assert.Contains(files, f => f.EndsWith("README.md"));
        Assert.DoesNotContain(files, f => f.Contains("bin"));
    }

    public void Dispose()
    {
        if (Directory.Exists(_tempPath))
        {
            Directory.Delete(_tempPath, true);
        }
    }
}
