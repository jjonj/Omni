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
        public event EventHandler<string> CommandOutputReceived;

        public async Task ExecuteCommand(string command)
        {
            await Task.Run(() =>
            {
                var processStartInfo = new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = $"/c {command}", // /c carries out the command then terminates
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };

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
                MainWindowTitle = p.MainWindowTitle // Can be empty for background processes
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
    }
}
