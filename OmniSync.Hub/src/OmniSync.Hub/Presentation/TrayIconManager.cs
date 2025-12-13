using System.Windows.Forms;
using System.Drawing;
using Microsoft.Extensions.Hosting;
using System.Threading;
using System.Threading.Tasks;
using System; // For AppContext
using System.IO; // For Path.Combine

namespace OmniSync.Hub.Presentation
{
    public class TrayIconManager : IHostedService, IDisposable
    {
        private readonly IHostApplicationLifetime _appLifetime;
        private TrayApplicationContext _applicationContext;
        private Thread _trayThread;

        public TrayIconManager(IHostApplicationLifetime appLifetime)
        {
            _appLifetime = appLifetime;
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _trayThread = new Thread(ThreadRun);
            _trayThread.IsBackground = true;
            _trayThread.SetApartmentState(ApartmentState.STA); // Set apartment state for UI components
            _trayThread.Start();

            _appLifetime.ApplicationStopping.Register(OnStopping);
            
            return Task.CompletedTask;
        }

        private void ThreadRun()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            _applicationContext = new TrayApplicationContext(_appLifetime);
            Application.Run(_applicationContext); // Start the message pump with our custom context
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            // Signal the ApplicationContext to exit its thread
            _applicationContext?.ExitThread();
            _trayThread?.Join(); // Wait for the tray thread to finish
            return Task.CompletedTask;
        }

        private void OnStopping()
        {
            Dispose();
        }

        public void Dispose()
        {
            // Dispose is called by the framework.
            // Cleanup happens in StopAsync via _applicationContext.ExitThread()
        }

        // Nested class to manage the NotifyIcon and its ApplicationContext
        private class TrayApplicationContext : ApplicationContext
        {
            private NotifyIcon _notifyIcon;
            private readonly IHostApplicationLifetime _appLifetime;

            public TrayApplicationContext(IHostApplicationLifetime appLifetime)
            {
                _appLifetime = appLifetime;
                InitializeComponent();
            }

            private void InitializeComponent()
            {
                _notifyIcon = new NotifyIcon();
                // Load the custom icon from the application's directory
                string iconPath = Path.Combine(AppContext.BaseDirectory, "OmniIcon.ico");
                if (File.Exists(iconPath))
                {
                    _notifyIcon.Icon = new Icon(iconPath);
                }
                else
                {
                    _notifyIcon.Icon = SystemIcons.Application; // Fallback to default
                }
                _notifyIcon.Text = "OmniSync Hub";
                _notifyIcon.Visible = true;

                // Create Context Menu
                var contextMenu = new ContextMenuStrip();
                contextMenu.Items.Add("E&xit", null, OnExit);

                _notifyIcon.ContextMenuStrip = contextMenu;

                _notifyIcon.DoubleClick += (sender, args) =>
                {
                    _notifyIcon.ShowBalloonTip(2000, "OmniSync Hub", "OmniSync Hub is running.", ToolTipIcon.Info);
                };
            }

            private void OnExit(object? sender, EventArgs e)
            {
                // Signal the main application to stop
                _appLifetime.StopApplication();
            }

            protected override void Dispose(bool disposing)
            {
                if (disposing && _notifyIcon != null)
                {
                    _notifyIcon.Visible = false;
                    _notifyIcon.Dispose();
                }
                base.Dispose(disposing);
            }
        }
    }

}
