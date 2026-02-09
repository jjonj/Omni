using Xunit;
using OmniProjectContext.Services;
using System.IO;
using System;

namespace OmniProjectContext.Tests;

public class GitServiceTests
{
    [Fact]
    public void GetRecentCommits_ShouldReturnRecentCommits()
    {
        var service = new GitHistoryService();
        var commits = service.GetRecentCommits(5);
        
        Assert.NotEmpty(commits);
        Assert.True(commits.Count <= 5);
    }
}
