using System;
using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

namespace OmniPower
{
    /// <summary>A single, cancelable ten-second gate shared by both power launchers.</summary>
    public static class PowerCountdown
    {
        /// <summary>False means the user canceled. No sleep/shutdown side effect should follow.</summary>
        public static bool Confirm(string action, ILog log)
        {
            using var form = new Form
            {
                Text = $"OmniPower - {action}",
                StartPosition = FormStartPosition.CenterScreen,
                FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false,
                MinimizeBox = false,
                ShowInTaskbar = true,
                TopMost = true,
                ClientSize = new Size(370, 145),
                Font = new Font(SystemFonts.MessageBoxFont?.FontFamily ?? FontFamily.GenericSansSerif, 11),
            };
            var message = new Label
            {
                AutoSize = false,
                Location = new Point(20, 15),
                Size = new Size(330, 67),
                TextAlign = ContentAlignment.MiddleCenter,
                Text = $"{action} in 10 seconds.\nCancel to keep this computer awake.",
            };
            var cancel = new Button
            {
                Text = "Cancel",
                Size = new Size(120, 34),
                Location = new Point(125, 95),
                DialogResult = DialogResult.Cancel,
            };
            form.Controls.Add(message);
            form.Controls.Add(cancel);
            form.CancelButton = cancel;
            form.AcceptButton = cancel;
            var timer = new System.Windows.Forms.Timer { Interval = 100 };
            var clock = new Stopwatch();
            timer.Tick += (_, _) =>
            {
                var remaining = Math.Max(0, 10 - (int)Math.Floor(clock.Elapsed.TotalSeconds));
                message.Text = $"{action} in {remaining} seconds.\nCancel to keep this computer awake.";
                if (clock.Elapsed >= TimeSpan.FromSeconds(10))
                {
                    timer.Stop();
                    form.DialogResult = DialogResult.OK;
                    form.Close();
                }
            };
            form.Shown += (_, _) => { clock.Start(); timer.Start(); };
            // Closing with the title-bar X is also a cancellation.
            var result = form.ShowDialog() == DialogResult.OK;
            timer.Stop();
            timer.Dispose();
            log.Write(result ? $"{action} countdown elapsed; proceeding." : $"{action} canceled during countdown.");
            return result;
        }
    }
}
