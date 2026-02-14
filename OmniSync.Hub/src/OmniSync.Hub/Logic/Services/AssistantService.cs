using System;
using System.IO;
using System.Diagnostics;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Text.Json;

namespace OmniSync.Hub.Logic.Services
{
    public class AthenaPaths
    {
        public string AthenaRoot { get; set; } = "";
        public string AthenaSrc { get; set; } = "";
        public string PythonExecutable { get; set; } = "python";
    }

    public class AssistantExecutionResult
    {
        public bool Success { get; set; }
        public string Output { get; set; } = "";
        public string Error { get; set; } = "";
        public int ExitCode { get; set; }
    }

    public class AssistantService
    {
        public AthenaPaths GetAthenaPaths()
        {
            // Hardcoded base for now as per project structure
            string root = @"D:\SSDProjects\Omni";
            string athenaRoot = Path.Combine(root, "OmniSync.Assistant", "Omni.Athena");
            string athenaSrc = Path.Combine(athenaRoot, "src");

            string pythonExe = "python"; // Default to system python

            // Look for venv
            string[] possibleVenvs = { ".venv", "venv" };
            foreach (var venv in possibleVenvs)
            {
                string venvPath = Path.Combine(athenaRoot, venv, "Scripts", "python.exe");
                if (File.Exists(venvPath))
                {
                    pythonExe = venvPath;
                    break;
                }
            }

            return new AthenaPaths
            {
                AthenaRoot = athenaRoot,
                AthenaSrc = athenaSrc,
                PythonExecutable = pythonExe
            };
        }

        public async Task<AssistantExecutionResult> ExecuteAsync(string command, IEnumerable<string> args, string? projectRoot = null)
        {
            var paths = GetAthenaPaths();
            projectRoot = DiscoverProjectRoot(projectRoot ?? Directory.GetCurrentDirectory());

            var processArgs = new List<string> { "-m", "athena" };
            
            // Inject --root if not already present, before the subcommand
            if (!processArgs.Contains("--root"))
            {
                processArgs.Add("--root");
                processArgs.Add(projectRoot);
            }

            if (!string.IsNullOrEmpty(command))
            {
                processArgs.Add(command);
            }
            processArgs.AddRange(args);

            var startInfo = new ProcessStartInfo
            {
                FileName = paths.PythonExecutable,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                WorkingDirectory = projectRoot
            };

            foreach (var arg in processArgs)
            {
                startInfo.ArgumentList.Add(arg);
            }

            // Set PYTHONPATH
            startInfo.EnvironmentVariables["PYTHONPATH"] = paths.AthenaSrc;
            startInfo.EnvironmentVariables["PYTHONIOENCODING"] = "utf-8";

            using var process = new Process { StartInfo = startInfo };
            
            var output = "";
            var error = "";

            process.OutputDataReceived += (s, e) => { if (e.Data != null) output += e.Data + Environment.NewLine; };
            process.ErrorDataReceived += (s, e) => { if (e.Data != null) error += e.Data + Environment.NewLine; };

            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();

            await process.WaitForExitAsync();

            return new AssistantExecutionResult
            {
                Success = process.ExitCode == 0,
                Output = output.Trim(),
                Error = error.Trim(),
                ExitCode = process.ExitCode
            };
        }

        private string DiscoverProjectRoot(string startDir)
        {
            var current = new DirectoryInfo(startDir);
            while (current != null)
            {
                if (current.GetDirectories(".omni").Length > 0 ||
                    current.GetDirectories(".athena").Length > 0 ||
                    current.GetDirectories(".git").Length > 0 ||
                    current.GetFiles(".athena_root").Length > 0)
                {
                    return current.FullName;
                }
                current = current.Parent;
            }
            return startDir;
        }
    }
}
