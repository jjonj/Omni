using System;
using System.Threading;
using System.Windows.Forms;
using OmniPower;

namespace OmniSleep
{
    internal static class Program
    {
        // Same lead time as the old sleep.bat, so the TV has time to power off before the PC sleeps.
        private static readonly TimeSpan SleepTvLeadTime = TimeSpan.FromSeconds(6);

        [STAThread]
        private static int Main(string[] args)
        {
            if (Array.IndexOf(args, "--input-test") >= 0)
            {
                return InputTest.Run(Array.IndexOf(args, "--simulate") >= 0);
            }

            using System.Threading.Mutex? instance = SingleInstance.TryAcquire(@"Local\OmniPower.Sleep");
            if (instance == null)
            {
                MessageBox.Show("OmniSleep is already running.", "OmniSleep", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }

            var log = new FileLog("sleep");
            try
            {
                PowerOptions options = PowerOptions.Parse(args);
                log.Write("Sleep requested.");

                if (PowerActions.StartTvOff(options, log))
                {
                    PowerActions.WaitForTvLeadTime(SleepTvLeadTime);
                }

                // Returns only once the PC has woken and the input decision says to stay awake.
                using (var guard = new SleepGuard(log, options.WakeConfirmationWindow))
                {
                    guard.Run();
                }

                Thread.Sleep(options.ExplorerRestartDelay);
                PowerActions.RestartExplorer(log);
                Thread.Sleep(TimeSpan.FromSeconds(3));
                log.Write("Sleep cycle finished.");
                return 0;
            }
            catch (Exception ex)
            {
                log.Write("Sleep failed: " + ex);
                MessageBox.Show("Sleep failed: " + ex.Message + Environment.NewLine + "Log: " + log.FilePath,
                    "OmniSleep", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }
    }
}
