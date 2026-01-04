using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class ProcessService
    {
        private readonly HubSettingsService _settingsService;
        public event EventHandler<string> CommandOutputReceived;

        public ProcessService(HubSettingsService settingsService)
        {
            _settingsService = settingsService;
        }

        public async Task ExecuteCommand(string command)
        {
            // Resolve mapping if available
            string finalCommand = command;
            var parts = command.Split(' ', 2);
            var firstPart = parts[0];
            var mappedPath = _settingsService.GetPath(firstPart);
            
            if (mappedPath != null)
            {
                if (parts.Length > 1)
                {
                    finalCommand = $"\"{mappedPath}\" {parts[1]}";
                }
                else
                {
                    finalCommand = $"\"{mappedPath}\"";
                }
            }

            await Task.Run(() =>
            {
                var processStartInfo = new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = $"/c {finalCommand}", // Removed extra quotes around finalCommand since we handled them above or if it's just a raw command
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };

                // Re-evaluate: if finalCommand has internal quotes, $"/c \"{finalCommand}\"" might be better or worse.
                // cmd.exe /c "C:\Path\To\Exe" args
                // Let's use the safer way:
                processStartInfo.Arguments = $"/c {finalCommand}";
                // Wait, if finalCommand is: "C:\Program Files\Chrome.exe" https://google.com
                // cmd /c "C:\Program Files\Chrome.exe" https://google.com
                // This usually works.


                using (var process = new Process { StartInfo = processStartInfo })
                {
                    process.OutputDataReceived += (sender, args) =>
                    {
                        if (!string.IsNullOrEmpty(args.Data))
                        {
                            CommandOutputReceived?.Invoke(this, args.Data + Environment.NewLine);
                        }
                    };
                    process.ErrorDataReceived += (sender, args) =>
                    {
                        if (!string.IsNullOrEmpty(args.Data))
                        {
                            CommandOutputReceived?.Invoke(this, "[ERROR] " + args.Data + Environment.NewLine);
                        }
                    };

                    process.Start();
                    process.BeginOutputReadLine();
                    process.BeginErrorReadLine();
                    process.WaitForExit();
                }
            });
        }

        public IEnumerable<ProcessInfo> ListProcesses()
        {
            return Process.GetProcesses().Select(p => new ProcessInfo
            {
                Id = p.Id,
                Name = p.ProcessName,
                MemoryUsage = p.WorkingSet64,
                CpuUsage = 0
            }).ToList();
        }

        public bool KillProcess(int processId)
        {
            try
            {
                var process = Process.GetProcessById(processId);
                process.Kill();
                return true;
            }
            catch (ArgumentException)
            {
                // Process with specified ID is not running.
                return false;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error killing process {processId}: {ex.Message}");
                return false;
            }
        }
    }

    public class ProcessInfo
    {
        public int Id { get; set; }
        public string Name { get; set; }
        public string MainWindowTitle { get; set; }
        public long MemoryUsage { get; set; }
        public double CpuUsage { get; set; }
    }
}
