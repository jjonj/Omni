using System;
using System.IO;
using System.Text;
using System.Windows.Forms;
using System.Threading.Tasks;

namespace OmniSync.Hub.Infrastructure.Services
{
    public static class CrashHandler
    {
        private static string LogPath => Path.Combine(AppContext.BaseDirectory, "hub_crash_log.log");

        public static void Initialize()
        {
            AppDomain.CurrentDomain.UnhandledException += (sender, e) =>
            {
                HandleCrash("UnhandledException", e.ExceptionObject as Exception);
            };

            TaskScheduler.UnobservedTaskException += (sender, e) =>
            {
                HandleCrash("UnobservedTaskException", e.Exception);
                e.SetObserved();
            };
        }

        public static void HandleCrash(string type, Exception? ex)
        {
            string message = ex?.Message ?? "No exception message available.";
            string stackTrace = ex?.StackTrace ?? "No stack trace available.";
            
            var sb = new StringBuilder();
            sb.AppendLine($"--- CRASH DETECTED [{DateTime.Now}] ---");
            sb.AppendLine($"Type: {type}");
            sb.AppendLine($"Message: {message}");
            sb.AppendLine($"StackTrace: {stackTrace}");

            if (ex?.InnerException != null)
            {
                sb.AppendLine($"InnerMessage: {ex.InnerException.Message}");
                sb.AppendLine($"InnerStackTrace: {ex.InnerException.StackTrace}");
            }

            sb.AppendLine("----------------------------------------");
            sb.AppendLine();

            Console.WriteLine(sb.ToString());

            try
            {
                File.AppendAllText(LogPath, sb.ToString());
                
                // Try to write to solution root as well
                try
                {
                    var rootLogPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "..", "hub_crash_log.log");
                    File.AppendAllText(rootLogPath, sb.ToString());
                }
                catch { /* Ignore errors writing to root */ }
            }
            catch { /* If we can't log to file, we are in deep trouble */ }

            // Show MessageBox to user
            string displayMessage = $"OmniSync Hub has encountered a fatal error and needs to close.\n\n" +
                                   $"Type: {type}\n" +
                                   $"Message: {message}\n\n" +
                                   $"Details have been logged to {LogPath}";

            MessageBox.Show(displayMessage, "OmniSync Hub - Fatal Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            
            // Exit the application
            Environment.Exit(1);
        }
    }
}