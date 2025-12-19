using System.Windows.Forms;
using WinFormsApp = System.Windows.Forms.Application;
using WpfApp = System.Windows.Application;
using System.Drawing;
using Microsoft.Extensions.Hosting;
using System.Threading;
using System.Threading.Tasks;
using System; // For AppContext
using System.IO; // For Path.Combine
using System.Windows; // For WPF Window and Application
using OmniSync.Hub.Logic.Monitoring; // For HubMonitorService
using OmniSync.Hub.Infrastructure.Services; // Add this using directive
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Presentation
{
    public class TrayIconManager : IHostedService, IDisposable
    {
        private readonly IHostApplicationLifetime _appLifetime;
        private readonly HubMonitorService _hubMonitorService; // New: Reference to HubMonitorService
        private readonly InputService _inputService; // Add InputService
        private readonly ShutdownService _shutdownService;
        private readonly RegistryService _registryService;
        private TrayApplicationContext _applicationContext;
        private Thread _trayThread;

        public TrayIconManager(IHostApplicationLifetime appLifetime, HubMonitorService hubMonitorService, InputService inputService, ShutdownService shutdownService, RegistryService registryService) // Add InputService to constructor
        {
            _appLifetime = appLifetime;
            _hubMonitorService = hubMonitorService; // Assign the injected service
            _inputService = inputService; // Assign the injected service
            _shutdownService = shutdownService;
            _registryService = registryService;
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
            // Initialize WPF Application on this thread
            var app = new WpfApp();
            app.ShutdownMode = System.Windows.ShutdownMode.OnExplicitShutdown; // Manage shutdown manually

            WinFormsApp.EnableVisualStyles(); // Enable visual styles for WinForms NotifyIcon
            WinFormsApp.SetCompatibleTextRenderingDefault(false); // For WinForms interop

            _applicationContext = new TrayApplicationContext(_appLifetime, app, _hubMonitorService, _inputService, _shutdownService, _registryService); // Pass hubMonitorService and inputService
            WinFormsApp.Run(_applicationContext); // Start the message pump with our custom context
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            // Signal the ApplicationContext to exit its thread
            _applicationContext?.ExitThread();
            // _trayThread?.Join(); // Do not join here. This will cause a deadlock because the call to StopApplication() originates from this thread.
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
            private readonly WpfApp _wpfApplication;
            private readonly HubMonitorService _hubMonitorService; // New: Reference to HubMonitorService
            private readonly InputService _inputService; // Add InputService
            private readonly ShutdownService _shutdownService;
            private readonly RegistryService _registryService;
            private MainWindow _mainWindow;

            public TrayApplicationContext(IHostApplicationLifetime appLifetime, WpfApp wpfApplication, HubMonitorService hubMonitorService, InputService inputService, ShutdownService shutdownService, RegistryService registryService) // Add InputService to constructor
            {
                _appLifetime = appLifetime;
                _wpfApplication = wpfApplication; // Store reference to the WPF Application instance
                _hubMonitorService = hubMonitorService; // Assign the injected service
                _inputService = inputService; // Assign the injected service
                _shutdownService = shutdownService;
                _registryService = registryService;
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
                _notifyIcon.Visible = true; // Make visible first

                // Create and store the WPF main window, passing the HubMonitorService
                _mainWindow = new MainWindow(_hubMonitorService, _inputService, _shutdownService, _registryService);

                // Create Context Menu
                var contextMenu = new ContextMenuStrip();
                var showWindowMenuItem = new ToolStripMenuItem("S&how Window", null, OnShowWindow);
                var hideWindowMenuItem = new ToolStripMenuItem("H&ide Window", null, OnHideWindow);
                var exitMenuItem = new ToolStripMenuItem("E&xit", null, OnExit);

                contextMenu.Items.Add(showWindowMenuItem);
                contextMenu.Items.Add(hideWindowMenuItem);
                contextMenu.Items.Add(new ToolStripSeparator()); // Separator
                contextMenu.Items.Add(exitMenuItem);

                _notifyIcon.ContextMenuStrip = contextMenu;

                _notifyIcon.MouseClick += OnMouseClick; // Handle left-click to show/hide window
            }

            private void OnMouseClick(object? sender, MouseEventArgs e)
            {
                if (e.Button == MouseButtons.Left)
                {
                    if (_mainWindow.IsVisible)
                    {
                        _mainWindow.Hide();
                    }
                    else
                    {
                        _mainWindow.Show();
                        _mainWindow.Activate(); // Bring to foreground
                    }
                }
            }

            private void OnTestOption(object? sender, EventArgs e)
            {
                // System.Windows.Forms.MessageBox.Show("Test Option clicked!"); // Debug
            }

            private void OnShowWindow(object? sender, EventArgs e)
            {
                // System.Windows.Forms.MessageBox.Show("Show Window option clicked!"); // Debug
                _mainWindow.Show();
                _mainWindow.Activate();
            }

            private void OnHideWindow(object? sender, EventArgs e)
            {
                // System.Windows.Forms.MessageBox.Show("Hide Window option clicked!"); // Debug
                _mainWindow.Hide();
            }

            private void OnExit(object? sender, EventArgs e)
            {
                // Signal the main application to stop
                _appLifetime.StopApplication();
            }

            protected override void Dispose(bool disposing)
            {
                if (disposing)
                {
                    if (_notifyIcon != null)
                    {
                        _notifyIcon.Visible = false;
                        _notifyIcon.Dispose();
                    }
                    if (_mainWindow != null)
                    {
                        _mainWindow.Close(); // Close the WPF window
                    }
                    _wpfApplication.Shutdown(); // Shut down the WPF Application
                }
                base.Dispose(disposing);
            }
        }
    }

}
