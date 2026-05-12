using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Infrastructure.Services
{
    public interface ITerminalService
    {
        Task<int> SpawnTerminalAsync(string shell, string? workspace = null);
        Task<bool> SendCommandAsync(int pid, string command);
        void StopTerminal(int pid);
        event EventHandler<TerminalOutputEventArgs> OutputReceived;
    }

    public class TerminalOutputEventArgs : EventArgs
    {
        public int Pid { get; set; }
        public string Data { get; set; } = string.Empty;
    }

    public class TerminalService : ITerminalService, IDisposable
    {
        private readonly ILogger<TerminalService> _logger;
        private readonly HubMonitorService _monitorService;
        private readonly ConcurrentDictionary<int, Process> _terminals = new();

        public event EventHandler<TerminalOutputEventArgs>? OutputReceived;

        public TerminalService(ILogger<TerminalService> logger, HubMonitorService monitorService)
        {
            _logger = logger;
            _monitorService = monitorService;
        }

        public async Task<int> SpawnTerminalAsync(string shell, string? workspace = null)
        {
            _monitorService.AddLogMessage($"[TerminalService] Spawning terminal: {shell} in {workspace ?? "default"}");

            var psi = new ProcessStartInfo
            {
                FileName = shell,
                WorkingDirectory = workspace ?? "",
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            var process = new Process { StartInfo = psi };
            
            process.OutputDataReceived += (s, e) =>
            {
                if (!string.IsNullOrEmpty(e.Data))
                {
                    OutputReceived?.Invoke(this, new TerminalOutputEventArgs { Pid = process.Id, Data = e.Data });
                }
            };

            process.ErrorDataReceived += (s, e) =>
            {
                if (!string.IsNullOrEmpty(e.Data))
                {
                    OutputReceived?.Invoke(this, new TerminalOutputEventArgs { Pid = process.Id, Data = "[ERROR] " + e.Data });
                }
            };

            try
            {
                process.Start();
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();

                _terminals[process.Id] = process;
                _monitorService.AddLogMessage($"[TerminalService] Terminal spawned with PID: {process.Id}");
                return process.Id;
            }
            catch (Exception ex)
            {
                _monitorService.AddLogMessage($"[TerminalService] Failed to spawn terminal: {ex.Message}");
                throw;
            }
        }

        public async Task<bool> SendCommandAsync(int pid, string command)
        {
            if (_terminals.TryGetValue(pid, out var process))
            {
                try
                {
                    await process.StandardInput.WriteLineAsync(command);
                    return true;
                }
                catch (Exception ex)
                {
                    _monitorService.AddLogMessage($"[TerminalService] Failed to send command to PID {pid}: {ex.Message}");
                    return false;
                }
            }
            return false;
        }

        public void StopTerminal(int pid)
        {
            if (_terminals.TryRemove(pid, out var process))
            {
                try
                {
                    if (!process.HasExited)
                    {
                        process.Kill(true);
                    }
                }
                catch { }
                process.Dispose();
            }
        }

        public void Dispose()
        {
            foreach (var pid in _terminals.Keys)
            {
                StopTerminal(pid);
            }
        }
    }
}
