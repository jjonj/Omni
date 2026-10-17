using System;
using System.Diagnostics;
using System.IO;
using System.Threading;

namespace OmniPower
{
    public static class PowerActions
    {
        /// Runs the TV-off script without waiting for it, as the old shutdown.bat/sleep.bat did.
        /// Returns true when the script was started, so callers know whether to give it lead time.
        public static bool StartTvOff(PowerOptions options, ILog log)
        {
            string? script = options.TvOffScript;
            if (script == null)
            {
                log.Write("TV control disabled.");
                return false;
            }

            if (!File.Exists(script))
            {
                log.Write("TV off script not found, skipping TV control: " + script);
                return false;
            }

            log.Write("Starting TV off script.");
            Process.Start(new ProcessStartInfo("cmd.exe")
            {
                ArgumentList = { "/d", "/c", script },
                WorkingDirectory = Path.GetDirectoryName(script) ?? Environment.CurrentDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
            });
            return true;
        }

        /// Gives the TV script time to send its power-off command before the PC goes down.
        public static void WaitForTvLeadTime(TimeSpan leadTime)
        {
            Thread.Sleep(leadTime);
        }

        /// Equivalent of shutdown.exe /f /s /t 0. Returns the shutdown.exe exit code.
        public static int Shutdown()
        {
            using Process process = Process.Start(new ProcessStartInfo(
                Path.Combine(Environment.SystemDirectory, "shutdown.exe"), "/f /s /t 0")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
            }) ?? throw new InvalidOperationException("Could not start shutdown.exe.");

            process.WaitForExit();
            return process.ExitCode;
        }

        /// Blocks until the system resumes. Uses the same rundll32 call as the old sleep_guard.ps1.
        public static int SuspendToSleep()
        {
            using Process process = Process.Start(new ProcessStartInfo(
                Path.Combine(Environment.SystemDirectory, "rundll32.exe"), "powrprof.dll,SetSuspendState 0,1,0")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
            }) ?? throw new InvalidOperationException("Could not start rundll32.exe.");

            process.WaitForExit();
            return process.ExitCode;
        }

        /// Output of powercfg /lastwake, which names the device that woke the PC (if any).
        public static string LastWakeReport()
        {
            try
            {
                using Process process = Process.Start(new ProcessStartInfo(
                    Path.Combine(Environment.SystemDirectory, "powercfg.exe"), "/lastwake")
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                }) ?? throw new InvalidOperationException("Could not start powercfg.exe.");

                string output = process.StandardOutput.ReadToEnd() + process.StandardError.ReadToEnd();
                process.WaitForExit();
                return output.Trim();
            }
            catch (Exception ex)
            {
                return "powercfg /lastwake failed: " + ex.Message;
            }
        }

        /// Kills Explorer and starts it again. The old sleep.bat did this after the PC woke, which fixes a broken taskbar.
        public static void RestartExplorer(ILog log)
        {
            log.Write("Restarting Explorer.");
            foreach (Process explorer in Process.GetProcessesByName("explorer"))
            {
                try
                {
                    explorer.Kill();
                    explorer.WaitForExit(3000);
                }
                catch (Exception ex)
                {
                    log.Write("Could not stop an Explorer process: " + ex.Message);
                }
                finally
                {
                    explorer.Dispose();
                }
            }

            Process.Start(new ProcessStartInfo(
                Path.Combine(Environment.GetEnvironmentVariable("SystemRoot") ?? @"C:\Windows", "explorer.exe"))
            {
                UseShellExecute = true,
            });
        }
    }
}
