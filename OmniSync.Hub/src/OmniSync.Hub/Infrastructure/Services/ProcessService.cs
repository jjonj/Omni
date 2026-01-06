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
            string executable;
            string arguments = "";

            if (command.StartsWith("\""))
            {
                int nextQuote = command.IndexOf("\"", 1);
                if (nextQuote != -1)
                {
                    executable = command.Substring(1, nextQuote - 1);
                    arguments = command.Substring(nextQuote + 1).Trim();
                }
                else executable = command;
            }
            else
            {
                var parts = command.Split(' ', 2);
                executable = parts[0];
                if (parts.Length > 1) arguments = parts[1];
            }

            var mappedPath = _settingsService.GetPath(executable);
            
            if (mappedPath != null)
            {
                finalCommand = string.IsNullOrEmpty(arguments) ? $"\"{mappedPath}\"" : $"\"{mappedPath}\" {arguments}";
            }

            await Task.Run(() =>
            {
                var processStartInfo = new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = $"/c {finalCommand}", 
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

        public void WinActivate(string target)
        {
            // Intelligent activation: try exact title, partial title, then process name
            string script = $@"
$target = '{target.Replace("'", "''")}'
$wshell = New-Object -ComObject WScript.Shell
if ($wshell.AppActivate($target)) {{ exit }}

$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
foreach ($p in $procs) {{
    if ($wshell.AppActivate($p.Id)) {{ exit }}
}}
";
            Process.Start(new ProcessStartInfo
            {
                FileName = "powershell",
                Arguments = $"-Command \"{script.Replace("\n", " ").Replace("\r", "")}\"",
                CreateNoWindow = true,
                UseShellExecute = false
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
