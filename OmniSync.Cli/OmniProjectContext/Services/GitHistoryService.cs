using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Text;

namespace OmniProjectContext.Services;

public class GitHistoryService
{
    private readonly string _workingDirectory;

    public GitHistoryService(string workingDirectory = null)
    {
        _workingDirectory = workingDirectory;
    }

    public List<string> GetRecentCommits(int count)
    {
        var commits = new List<string>();
        try
        {
            var startInfo = new ProcessStartInfo
            {
                FileName = "git",
                Arguments = "log -n " + count + " --format=\"%B%n---\"",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                WorkingDirectory = _workingDirectory
            };

            using var process = Process.Start(startInfo);
            if (process == null) return commits;

            var output = process.StandardOutput.ReadToEnd();
            process.WaitForExit();

            if (!string.IsNullOrWhiteSpace(output))
            {
                var parts = output.Split(new[] { "\n---\n", "\r\n---\r\n", "---" }, StringSplitOptions.RemoveEmptyEntries);
                foreach (var part in parts)
                {
                    var trimmed = part.Trim();
                    if (!string.IsNullOrWhiteSpace(trimmed))
                    {
                        commits.Add(trimmed);
                    }
                }
            }
        }
        catch (Exception)
        {
            // Silently fail and return empty list
        }
        return commits;
    }
}
