using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Presentation.Hubs;
using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace OmniSync.Hub.Logic.Services
{
    public class WebReloaderService : IHostedService, IDisposable
    {
        private readonly ILogger<WebReloaderService> _logger;
        private readonly IHubContext<RpcApiHub> _hubContext;
        private FileSystemWatcher? _watcher;
        private readonly string _webPath;
        private DateTime _lastRefresh = DateTime.MinValue;
        private readonly TimeSpan _debounce = TimeSpan.FromMilliseconds(500);

        public WebReloaderService(ILogger<WebReloaderService> logger, IHubContext<RpcApiHub> hubContext)
        {
            _logger = logger;
            _hubContext = hubContext;

            // Root directory logic consistent with Program.cs and FileService
            _webPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "..", "OmniSync.Web", "www"));
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            if (Directory.Exists(_webPath))
            {
                _logger.LogInformation($"WebReloaderService: Watching {_webPath} for changes...");
                _watcher = new FileSystemWatcher(_webPath)
                {
                    IncludeSubdirectories = true,
                    NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName | NotifyFilters.DirectoryName,
                    Filter = "*.*"
                };

                _watcher.Changed += OnChanged;
                _watcher.Created += OnChanged;
                _watcher.Deleted += OnChanged;
                _watcher.Renamed += OnRenamed;
                _watcher.EnableRaisingEvents = true;
            }
            else
            {
                _logger.LogWarning($"WebReloaderService: Web path not found at {_webPath}. Auto-refresh disabled.");
            }

            return Task.CompletedTask;
        }

        private void OnRenamed(object sender, RenamedEventArgs e)
        {
            HandleChange(e.Name ?? "");
        }

        private void OnChanged(object sender, FileSystemEventArgs e)
        {
            HandleChange(e.Name ?? "");
        }

        private async void HandleChange(string fileName)
        {
            if (DateTime.Now - _lastRefresh < _debounce) return;
            
            // Ignore temporary files and system folders
            if (fileName.Contains(".git") || fileName.Contains(".omni") || fileName.Contains("~") || fileName.EndsWith(".tmp"))
            {
                return;
            }

            // Only refresh for web-related files to avoid excessive noise
            string ext = Path.GetExtension(fileName).ToLower();
            if (ext != ".html" && ext != ".js" && ext != ".css" && ext != ".json")
            {
                return;
            }

            _lastRefresh = DateTime.Now;
            _logger.LogInformation($"WebReloaderService: Change detected in {fileName}. Triggering browser refresh...");
            
            try
            {
                // Extensionless (dev-sync.js)
                await _hubContext.Clients.All.SendAsync("ReceiveDevRefresh", "");
                // Chrome Extension (background.js)
                await _hubContext.Clients.All.SendAsync("ReceiveBrowserCommand", "Refresh", "", false);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "WebReloaderService: Error sending refresh command.");
            }
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            _watcher?.Dispose();
            return Task.CompletedTask;
        }

        public void Dispose()
        {
            _watcher?.Dispose();
        }
    }
}
