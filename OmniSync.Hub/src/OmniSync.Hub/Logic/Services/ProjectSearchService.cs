using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
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
        public DateTime LastAccessed { get; set; }
    }

    public class ProjectSearchService
    {
        private readonly HubSettingsService _settingsService;
        private readonly ILogger<ProjectSearchService> _logger;
        private List<string> _recentWorkspaces = new();
        private const int MAX_RECENT = 10;

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

            await Task.Run(() =>
            {
                foreach (var root in roots)
                {
                    if (!Directory.Exists(root)) continue;

                    try
                    {
                        // Search top-level directories first as they are likely project roots
                        var directories = Directory.GetDirectories(root);
                        foreach (var dir in directories)
                        {
                            var dirName = Path.GetFileName(dir);
                            if (dirName.Contains(query, StringComparison.OrdinalIgnoreCase))
                            {
                                results.Add(new SearchResult
                                {
                                    Name = dirName,
                                    Path = dir,
                                    IsGitRepo = Directory.Exists(Path.Combine(dir, ".git")),
                                    LastAccessed = Directory.GetLastWriteTime(dir)
                                });
                            }
                            
                            // If it's a deep search, we could recurse, but for now lets keep it shallow for performance
                            // Or search one level deeper if no results found
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
    }
}
