using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic.Services
{
    public class ShutdownService
    {
        private readonly ILogger<ShutdownService> _logger;
        private readonly ProcessService _processService;
        private CancellationTokenSource? _shutdownCts;
        private DateTime? _scheduledTime;

        public event EventHandler<DateTime?>? ShutdownScheduled;

        public ShutdownService(ILogger<ShutdownService> logger, ProcessService processService)
        {
            _logger = logger;
            _processService = processService;
        }

        public void ScheduleShutdown(int minutes)
        {
            _shutdownCts?.Cancel();
            _shutdownCts = null;
            _scheduledTime = null;

            if (minutes <= 0)
            {
                _logger.LogInformation("Shutdown timer cancelled.");
                ShutdownScheduled?.Invoke(this, null);
                return;
            }

            _scheduledTime = DateTime.Now.AddMinutes(minutes);
            ShutdownScheduled?.Invoke(this, _scheduledTime);
            _shutdownCts = new CancellationTokenSource();
            var token = _shutdownCts.Token;

            _logger.LogInformation($"Shutdown scheduled in {minutes} minutes (at {_scheduledTime}).");

            Task.Run(async () =>
            {
                try
                {
                    await Task.Delay(TimeSpan.FromMinutes(minutes), token);
                    _logger.LogInformation("Shutdown timer expired. Executing shutdown command.");
                    await _processService.ExecuteCommand(@"C:\Windows\explorer.exe ""B:\GDrive\Tools\05 Automation\shutdown.bat""");
                }
                catch (TaskCanceledException)
                {
                    _logger.LogInformation("Shutdown task was cancelled.");
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Error executing scheduled shutdown.");
                }
            }, token);
        }

        public DateTime? GetScheduledTime() => _scheduledTime;
    }
}
