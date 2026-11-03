using System;
using System.Threading;
using System.Windows.Forms;
using OmniPower;

namespace OmniShutdown
{
    internal static class Program
    {
        // Same lead time as the old shutdown.bat, so the TV has time to power off before the PC goes down.
        private static readonly TimeSpan ShutdownTvLeadTime = TimeSpan.FromSeconds(8);

        [STAThread]
        private static int Main(string[] args)
        {
            using System.Threading.Mutex? instance = SingleInstance.TryAcquire(@"Local\OmniPower.Action");
            if (instance == null)
            {
                MessageBox.Show("OmniShutdown is already running.", "OmniShutdown", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }

            var log = new FileLog("shutdown");
            try
            {
                PowerOptions options = PowerOptions.Parse(args);
                log.Write("Shutdown requested.");

                PowerActions.StartTvOff(options, log); // Monitor control runs before the power action.
                if (!PowerCountdown.Confirm("Shutdown", log)) return 0;

                int exitCode = PowerActions.Shutdown();
                if (exitCode != 0)
                {
                    throw new InvalidOperationException("shutdown.exe exited with code " + exitCode + ".");
                }

                log.Write("Shutdown command sent.");
                return 0;
            }
            catch (Exception ex)
            {
                log.Write("Shutdown failed: " + ex);
                MessageBox.Show("Shutdown failed: " + ex.Message + Environment.NewLine + "Log: " + log.FilePath,
                    "OmniShutdown", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }
    }
}
