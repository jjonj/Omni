using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic.Services
{
    public class ScreenshotHostedService : BackgroundService
    {
        private readonly ScreenshotService _screenshotService;
        private readonly IConfiguration _configuration;
        private readonly ILogger<ScreenshotHostedService> _logger;
        private readonly string _screenshotFolder;

        public ScreenshotHostedService(
            ScreenshotService screenshotService,
            IConfiguration configuration,
            ILogger<ScreenshotHostedService> logger)
        {
            _screenshotService = screenshotService;
            _configuration = configuration;
            _logger = logger;
            _screenshotFolder = Path.Combine(AppContext.BaseDirectory, "Screenshots");
        }

        protected override async Task ExecuteAsync(CancellationToken stoppingToken)
        {
            _logger.LogInformation("ScreenshotHostedService is starting.");

            while (!stoppingToken.IsCancellationRequested)
            {
                var enabled = _configuration.GetValue<bool>("ScreenshotSettings:Enabled", true);
                var intervalMinutes = _configuration.GetValue<int>("ScreenshotSettings:IntervalMinutes", 5);

                if (enabled)
                {
                    try
                    {
                        CaptureAndCleanup();
                    }
                    catch (Exception ex)
                    {
                        _logger.LogError(ex, "Error during screenshot capture or cleanup.");
                    }
                }

                // Wait for the next interval
                await Task.Delay(TimeSpan.FromMinutes(intervalMinutes), stoppingToken);
            }

            _logger.LogInformation("ScreenshotHostedService is stopping.");
        }

        private void CaptureAndCleanup()
        {
            // Capture
            string timestamp = DateTime.Now.ToString("yyyyMMdd_HHmmss");
            string filePath = Path.Combine(_screenshotFolder, $"screenshot_{timestamp}.jpg");
            
            _logger.LogInformation("Capturing screenshot to {FilePath}", filePath);
            _screenshotService.CapturePrimaryScreen(filePath);

            // Cleanup
            CleanupOldScreenshots();
        }

        private void CleanupOldScreenshots()
        {
            if (!Directory.Exists(_screenshotFolder)) return;

            var threshold = DateTime.Now.AddHours(-24);
            var files = Directory.GetFiles(_screenshotFolder, "screenshot_*.jpg");

            foreach (var file in files)
            {
                var fileInfo = new FileInfo(file);
                if (fileInfo.CreationTime < threshold)
                {
                    try
                    {
                        _logger.LogInformation("Deleting old screenshot {FileName}", fileInfo.Name);
                        fileInfo.Delete();
                    }
                    catch (Exception ex)
                    {
                        _logger.LogWarning(ex, "Failed to delete old screenshot {FileName}", fileInfo.Name);
                    }
                }
            }
        }
    }
}
