using System;

namespace OmniPower
{
    public sealed class PowerOptions
    {
        /// The TV-off helper from the old automation folder. Skipped when missing, so the PC still sleeps/shuts down.
        public const string DefaultTvOffScript = @"B:\GDrive\Tools\05 Automation\TVActive3\tv_off.bat";

        /// Time after a sleep attempt during which keyboard/mouse input is treated as a wake request.
        public TimeSpan WakeConfirmationWindow { get; init; } = TimeSpan.FromSeconds(30);

        /// Delay before Explorer is restarted after the PC stays awake, so the shell has time to settle.
        public TimeSpan ExplorerRestartDelay { get; init; } = TimeSpan.FromSeconds(10);

        /// Null disables TV control (--no-tv).
        public string? TvOffScript { get; init; } = DefaultTvOffScript;

        /// Parses --no-tv and --tv-script "<path>". Unknown arguments are ignored.
        public static PowerOptions Parse(string[] args)
        {
            string? tvScript = DefaultTvOffScript;
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--no-tv")
                {
                    tvScript = null;
                }
                else if (args[i] == "--tv-script" && i + 1 < args.Length)
                {
                    tvScript = args[++i];
                }
            }

            return new PowerOptions { TvOffScript = tvScript };
        }
    }
}

