using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.IO;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic.Services
{
        public enum ShutdownMode
        {
            Shutdown,
            Sleep
        }
    
                public class ShutdownService
    
                {
    
                    private readonly ILogger<ShutdownService> _logger;
    
                    private readonly ProcessService _processService;
    
                    private readonly AudioService _audioService;
    
                    private readonly FileService _fileService;
    
                    private CancellationTokenSource? _shutdownCts;
    
                    private DateTime? _scheduledTime;
    
                    private ShutdownMode _currentMode = ShutdownMode.Shutdown;
    
            
    
                    public event EventHandler<DateTime?>? ShutdownScheduled;
    
                    public event EventHandler<ShutdownMode>? ModeChanged;
    
            
    
                    public ShutdownService(ILogger<ShutdownService> logger, ProcessService processService, AudioService audioService, FileService fileService)
    
                    {
    
                        _logger = logger;
    
                        _processService = processService;
    
                        _audioService = audioService;
    
                        _fileService = fileService;
    
                    }
    
            
                    public ShutdownMode GetCurrentMode() => _currentMode;
    
            public void SetMode(ShutdownMode mode)
            {
                if (_currentMode != mode)
                {
                    _currentMode = mode;
                    _logger.LogInformation($"Shutdown mode changed to: {mode}");
                    ModeChanged?.Invoke(this, _currentMode);
                }
            }
    
            public void ScheduleShutdown(int minutes)
            {
                _shutdownCts?.Cancel();
                _shutdownCts = null;
                _scheduledTime = null;
    
                if (minutes <= 0)
                {
                    _logger.LogInformation($"{_currentMode} timer cancelled.");
                    ShutdownScheduled?.Invoke(this, null);
                    return;
                }
    
                _scheduledTime = DateTime.Now.AddMinutes(minutes);
                ShutdownScheduled?.Invoke(this, _scheduledTime);
                _shutdownCts = new CancellationTokenSource();
                var token = _shutdownCts.Token;
    
                _logger.LogInformation($"{_currentMode} scheduled in {minutes} minutes (at {_scheduledTime}).");
    
                Task.Run(async () =>
                {
                    try
                    {
                        if (minutes > 1)
                        {
                            await Task.Delay(TimeSpan.FromMinutes(minutes - 1), token);
                            _logger.LogInformation($"Playing shutdown warning sound.");
                            string soundPath = _fileService.GetResourcePath(Path.Combine("Resources", "shut1min.wav"));
                            _audioService.PlaySound(soundPath);
                            await Task.Delay(TimeSpan.FromMinutes(1), token);
                        }
                        else
                        {
                            await Task.Delay(TimeSpan.FromMinutes(minutes), token);
                        }

                        _logger.LogInformation($"{_currentMode} timer expired. Executing command.");
                        
                        string command = _currentMode == ShutdownMode.Shutdown 
                            ? @"C:\Windows\explorer.exe ""B:\GDrive\Tools\05 Automation\shutdown.bat"""
                            : @"C:\Windows\explorer.exe ""B:\GDrive\Tools\05 Automation\sleep.bat""";
    
                        await _processService.ExecuteCommand(command);
                    }
                    catch (TaskCanceledException)
                    {
                        _logger.LogInformation($"{_currentMode} task was cancelled.");
                    }
                    catch (Exception ex)
                    {
                        _logger.LogError(ex, $"Error executing scheduled {_currentMode}.");
                    }
                }, token);
            }
    
            public DateTime? GetScheduledTime() => _scheduledTime;
        }
}
