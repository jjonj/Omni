using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic.Services
{
    public class SearchResult
    {
        public string Name { get; set; } = "";
        public string Path { get; set; } = "";
        public bool IsGitRepo { get; set; }
        public bool IsFile { get; set; }
        public DateTime LastAccessed { get; set; }
    }

    public class ProjectSearchService
    {
        private readonly HubSettingsService _settingsService;
        private readonly ILogger<ProjectSearchService> _logger;
        private List<string> _recentWorkspaces = new();
        private const int MAX_RECENT = 10;
        private const int DEFAULT_MAX_RESULTS = 60;
        private const int DEFAULT_MAX_DEPTH = 32;
        private const int DEFAULT_MAX_DIRECTORIES = 20000;
        private static readonly HashSet<string> IgnoredDirectoryNames = new(StringComparer.OrdinalIgnoreCase)
        {
            "bin", "obj", "dist", "build", "out", "target", "node_modules", ".git", ".vs", ".idea",
            ".vscode", ".gradle", "coverage", "artifacts", "logs", "tmp", "temp", ".cache"
        };

        public ProjectSearchService(HubSettingsService settingsService, ILogger<ProjectSearchService> logger)
        {
            _settingsService = settingsService;
            _logger = logger;
        }

        public void RecordWorkspaceAccess(string path)
        {
            _recentWorkspaces.Remove(path);
            _recentWorkspaces.Insert(0, path);
            if (_recentWorkspaces.Count > MAX_RECENT)
            {
                _recentWorkspaces = _recentWorkspaces.Take(MAX_RECENT).ToList();
            }
        }

        public List<string> GetRecentWorkspaces() => _recentWorkspaces;

        public async Task<List<SearchResult>> SearchAsync(string query)
        {
            if (string.IsNullOrWhiteSpace(query)) return new List<SearchResult>();

            var roots = _settingsService.Settings.ProjectRoots.Where(r => r.IsEnabled).Select(r => r.Path).ToList();
            var results = new List<SearchResult>();
            var maxResults = DEFAULT_MAX_RESULTS;
            var maxDepth = DEFAULT_MAX_DEPTH;
            var maxDirectories = DEFAULT_MAX_DIRECTORIES;
            var matcher = BuildMatcher(query);

            await Task.Run(() =>
            {
                foreach (var root in roots)
                {
                    if (!Directory.Exists(root)) continue;

                    try
                    {
                        var queue = new Queue<(string path, int depth)>();
                        queue.Enqueue((root, 0));

                        while (queue.Count > 0 && results.Count < maxResults && maxDirectories > 0)
                        {
                            var (current, depth) = queue.Dequeue();
                            maxDirectories--;

                            IEnumerable<string> directories;
                            try
                            {
                                directories = Directory.EnumerateDirectories(current);
                            }
                            catch
                            {
                                continue;
                            }

                            foreach (var dir in directories)
                            {
                                if (results.Count >= maxResults) break;
                                var dirName = Path.GetFileName(dir);
                                if (ShouldSkipDirectory(dirName, dir)) continue;
                                if (matcher(dirName, dir))
                                {
                                    results.Add(new SearchResult
                                    {
                                        Name = dirName,
                                        Path = dir,
                                        IsGitRepo = Directory.Exists(Path.Combine(dir, ".git")),
                                        IsFile = false,
                                        LastAccessed = Directory.GetLastWriteTime(dir)
                                    });
                                }

                                if (depth < maxDepth)
                                {
                                    queue.Enqueue((dir, depth + 1));
                                }
                            }

                            IEnumerable<string> files;
                            try
                            {
                                files = Directory.EnumerateFiles(current);
                            }
                            catch
                            {
                                continue;
                            }

                            foreach (var file in files)
                            {
                                if (results.Count >= maxResults) break;
                                if (ShouldSkipPath(file)) continue;
                                var fileName = Path.GetFileName(file);
                                if (matcher(fileName, file))
                                {
                                    results.Add(new SearchResult
                                    {
                                        Name = fileName,
                                        Path = file,
                                        IsGitRepo = false,
                                        IsFile = true,
                                        LastAccessed = File.GetLastWriteTime(file)
                                    });
                                }
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        _logger.LogError(ex, $"Error searching root: {root}");
                    }
                }
            });

            return results.OrderByDescending(r => r.LastAccessed).ToList();
        }

        private static bool ShouldSkipDirectory(string name, string fullPath)
        {
            if (IgnoredDirectoryNames.Contains(name)) return true;
            return ShouldSkipPath(fullPath);
        }

        private static bool ShouldSkipPath(string path)
        {
            var segments = path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            foreach (var segment in segments)
            {
                if (IgnoredDirectoryNames.Contains(segment)) return true;
            }
            return false;
        }

        private static Func<string, string, bool> BuildMatcher(string query)
        {
            if (query.IndexOfAny(new[] { '*', '?' }) >= 0)
            {
                var regex = BuildWildcardRegex(query);
                return (name, fullPath) =>
                    regex.IsMatch(name) || regex.IsMatch(fullPath.Replace('\\', '/'));
            }

            return (name, fullPath) =>
                name.Contains(query, StringComparison.OrdinalIgnoreCase) ||
                fullPath.Contains(query, StringComparison.OrdinalIgnoreCase);
        }

        private static Regex BuildWildcardRegex(string pattern)
        {
            var escaped = Regex.Escape(pattern)
                .Replace("\\*", ".*")
                .Replace("\\?", ".");
            return new Regex(escaped, RegexOptions.IgnoreCase | RegexOptions.Compiled);
        }
    }
}
