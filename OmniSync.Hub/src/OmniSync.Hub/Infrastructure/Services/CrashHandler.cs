using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace OmniSync.Hub.Infrastructure.Services
{
    public static class CrashHandler
    {
        private static readonly object LogLock = new();
        private static readonly string RunId = $"{DateTime.UtcNow:yyyyMMddTHHmmssfffZ}-pid{Environment.ProcessId}";
        private static int _initialized;

        // Store diagnostics outside the build directory, so rebuilding or restarting cannot erase them.
        public static string LogDirectory => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OmniSync", "Logs");

        public static void Initialize()
        {
            // Program initializes this before building the host and again before Run().
            // Registering twice previously installed duplicate exception handlers.
            if (Interlocked.Exchange(ref _initialized, 1) != 0) return;

            WriteLifecycleMarker("START");
            AppDomain.CurrentDomain.ProcessExit += (_, _) => WriteLifecycleMarker("PROCESS_EXIT");

            AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            {
                HandleCrash("UnhandledException", e.ExceptionObject as Exception);
            };

            TaskScheduler.UnobservedTaskException += (_, e) =>
            {
                HandleCrash("UnobservedTaskException", e.Exception, shouldExit: false);
                e.SetObserved();
            };
        }

        public static void HandleCrash(string type, Exception? ex, bool shouldExit = true)
        {
            var sb = new StringBuilder();
            sb.AppendLine($"--- {(shouldExit ? "FATAL CRASH" : "NON-FATAL ERROR")} [{DateTimeOffset.Now:O}] ---");
            sb.AppendLine($"Type: {type}");
            sb.AppendLine($"Run: {RunId}");
            sb.AppendLine($"Process ID: {Environment.ProcessId}");
            sb.AppendLine($"Thread ID: {Environment.CurrentManagedThreadId}");
            sb.AppendLine($"Runtime: {Environment.Version}");
            sb.AppendLine($"Executable: {Environment.ProcessPath ?? AppContext.BaseDirectory}");
            sb.AppendLine($"Exception: {ex?.ToString() ?? "No exception details available."}");
            sb.AppendLine("----------------------------------------");
            sb.AppendLine();

            var reportPath = WriteReport(sb.ToString());
            try { Console.Error.WriteLine(sb.ToString()); }
            catch { /* Console may be unavailable during a fatal shutdown. */ }

            if (!shouldExit) return;

            string displayMessage = "OmniSync Hub has encountered a fatal error and needs to close.\n\n" +
                                    $"Type: {type}\n" +
                                    $"Message: {ex?.Message ?? "No exception message available."}\n\n" +
                                    $"Crash report: {reportPath}";

            MessageBox.Show(displayMessage, "OmniSync Hub - Fatal Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Environment.Exit(1);
        }

        // Visible to the test assembly: two errors must always get separate reports, with no overwrite.
        internal static string WriteReport(string content, string? directoryOverride = null)
        {
            lock (LogLock)
            {
                try
                {
                    var directory = directoryOverride ?? LogDirectory;
                    Directory.CreateDirectory(directory);
                    var reportPath = Path.Combine(directory,
                        $"hub_crash_{DateTime.UtcNow:yyyyMMdd_HHmmss_fff}_{Environment.ProcessId}_{Guid.NewGuid():N}.log");
                    File.WriteAllText(reportPath, content, Encoding.UTF8);
                    File.AppendAllText(Path.Combine(directory, "hub_crash_log.log"), content, Encoding.UTF8);
                    return reportPath;
                }
                catch
                {
                    // Last-resort fallback if the user's local diagnostics directory is inaccessible.
                    try
                    {
                        var fallbackPath = Path.Combine(AppContext.BaseDirectory, "hub_crash_log.log");
                        File.AppendAllText(fallbackPath, content, Encoding.UTF8);
                        return fallbackPath;
                    }
                    catch { return "(unable to save crash report)"; }
                }
            }
        }

        private static void WriteLifecycleMarker(string eventName)
        {
            lock (LogLock)
            {
                try
                {
                    Directory.CreateDirectory(LogDirectory);
                    File.AppendAllText(Path.Combine(LogDirectory, "hub_sessions.log"),
                        $"{DateTimeOffset.Now:O} {eventName} {RunId}{Environment.NewLine}", Encoding.UTF8);
                }
                catch { /* Diagnostics must never prevent the Hub from starting or exiting. */ }
            }
        }
    }
}
