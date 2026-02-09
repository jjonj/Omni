using Xunit;
using OmniProjectContext.Services;
using System.IO;
using System;
using System.Diagnostics;
using System.Collections.Generic;

namespace OmniProjectContext.Tests;

public class GitIntegrationTests : IDisposable
{
    private readonly string _tempRepoPath;

    public GitIntegrationTests()
    {
        _tempRepoPath = Path.Combine(Path.GetTempPath(), "opc-test-" + Guid.NewGuid().ToString());
        Directory.CreateDirectory(_tempRepoPath);
        RunGitCommand("init");
        RunGitCommand("config user.email \"test@example.com\"");
        RunGitCommand("config user.name \"Test User\"");
    }

    private void RunGitCommand(string args)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = "git",
            Arguments = args,
            WorkingDirectory = _tempRepoPath,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        using var process = Process.Start(startInfo);
        process?.WaitForExit();
    }

    [Fact]
    public void GitHistoryService_GetRecentCommits_ShouldReturnDetailedCommits()
    {
        for (int i = 1; i <= 3; i++)
        {
            string fileName = $"test{i}.txt";
            File.WriteAllText(Path.Combine(_tempRepoPath, fileName), "content");
            RunGitCommand("add " + fileName);
            RunGitCommand("commit -m \"Commit " + i + "\n\nBody message " + i + "\"");
        }

        var service = new GitHistoryService(_tempRepoPath);
        var commits = service.GetRecentCommits(2);
        
        Assert.Equal(2, commits.Count);
        Assert.Contains("Commit 3", commits[0]);
        Assert.Contains("Body message 3", commits[0]);
        Assert.Contains("Commit 2", commits[1]);
        Assert.Contains("Body message 2", commits[1]);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_tempRepoPath))
            {
                Directory.Delete(_tempRepoPath, true);
            }
        }
        catch
        {
            // Ignore cleanup errors in tests
        }
    }
}
