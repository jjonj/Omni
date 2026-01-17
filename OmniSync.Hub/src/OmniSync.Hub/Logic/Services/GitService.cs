using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services
{
    public class GitService
    {
        private readonly ILogger<GitService> _logger;

        public GitService(ILogger<GitService> logger)
        {
            _logger = logger;
        }

        public async Task<string> RunGitCommand(string workingDir, string arguments)
        {
            try
            {
                var startInfo = new ProcessStartInfo
                {
                    FileName = "git",
                    Arguments = arguments,
                    WorkingDirectory = workingDir,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    StandardOutputEncoding = Encoding.UTF8
                };

                using var process = new Process { StartInfo = startInfo };
                process.Start();

                string output = await process.StandardOutput.ReadToEndAsync();
                string error = await process.StandardError.ReadToEndAsync();

                await process.WaitForExitAsync();

                if (process.ExitCode != 0)
                {
                    _logger.LogWarning($"Git command failed with exit code {process.ExitCode}: {error}");
                    return $"Error: {error}";
                }

                return output;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Exception running git command in {workingDir}");
                return $"Exception: {ex.Message}";
            }
        }

        public async Task<string> GetGitLog(string path, int count = 20)
        {
            // %H: commit hash, %an: author name, %ar: author date (relative), %s: subject
            string format = "--pretty=format:\"%H|%an|%ar|%s\"";
            return await RunGitCommand(path, $"log -n {count} {format}");
        }

        public async Task<string> GetCommitDiff(string path, string commitHash)
        {
            // Show diff for specific commit
            return await RunGitCommand(path, $"show {commitHash} --unified=3");
        }

        public bool IsGitRepository(string path)
        {
            try
            {
                return Directory.Exists(Path.Combine(path, ".git"));
            }
            catch
            {
                return false;
            }
        }
    }
}
