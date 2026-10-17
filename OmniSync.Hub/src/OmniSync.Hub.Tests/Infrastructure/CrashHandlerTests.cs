using System;
using System.IO;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Tests.Infrastructure;

public class CrashHandlerTests
{
    [Fact]
    public void CrashReportsAreUniqueAndEarlierReportsArePreserved()
    {
        var directory = Path.Combine(Path.GetTempPath(), "omnihub-crash-tests-" + Guid.NewGuid().ToString("N"));
        try
        {
            var first = CrashHandler.WriteReport("First crash stack trace", directory);
            var second = CrashHandler.WriteReport("Second crash stack trace", directory);

            Assert.NotEqual(first, second);
            Assert.Contains("First crash stack trace", File.ReadAllText(first));
            Assert.Contains("Second crash stack trace", File.ReadAllText(second));

            var combined = File.ReadAllText(Path.Combine(directory, "hub_crash_log.log"));
            Assert.Contains("First crash stack trace", combined);
            Assert.Contains("Second crash stack trace", combined);
        }
        finally
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
        }
    }
}
