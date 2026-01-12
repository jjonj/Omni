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
using OmniSync.Hub.Logic;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Presentation
{
    public class TrayIconManager : IHostedService, IDisposable
    {
        private readonly IHostApplicationLifetime _appLifetime;
        private readonly HubMonitorService _hubMonitorService; // New: Reference to HubMonitorService
        private readonly InputService _inputService; // Add InputService
        private readonly ShutdownService _shutdownService;
        private readonly RegistryService _registryService;
        private readonly HubSettingsService _settingsService;
        private readonly GlobalHotkeyService _hotkeyService;
        private readonly KeyboardHook _keyboardHook;
        private readonly AiCliService _aiCliService;
        private readonly LayoutCaptureService _layoutCaptureService;
        private readonly ProjectLauncherService _projectLauncherService;
        private readonly ILogger<TrayIconManager> _logger;
        private TrayApplicationContext _applicationContext;
        private Thread _trayThread;

        public TrayIconManager(IHostApplicationLifetime appLifetime, HubMonitorService hubMonitorService, InputService inputService, ShutdownService shutdownService, RegistryService registryService, HubSettingsService settingsService, GlobalHotkeyService hotkeyService, KeyboardHook keyboardHook, AiCliService aiCliService, LayoutCaptureService layoutCaptureService, ProjectLauncherService projectLauncherService, ILogger<TrayIconManager> logger)
        {
            _appLifetime = appLifetime;
            _hubMonitorService = hubMonitorService;
            _inputService = inputService;
            _shutdownService = shutdownService;
            _registryService = registryService;
            _settingsService = settingsService;
            _hotkeyService = hotkeyService;
            _keyboardHook = keyboardHook;
            _aiCliService = aiCliService;
            _layoutCaptureService = layoutCaptureService;
            _projectLauncherService = projectLauncherService;
            _logger = logger;
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation("TrayIconManager: StartAsync called. Starting tray thread.");
            _trayThread = new Thread(ThreadRun);
            _trayThread.IsBackground = true;
            _trayThread.SetApartmentState(ApartmentState.STA); // Set apartment state for UI components
            _trayThread.Start();

            return Task.CompletedTask;
        }

        private void ThreadRun()
        {
            try
            {
                _logger.LogInformation("TrayIconManager: ThreadRun started.");
                // Initialize WPF Application on this thread
                var app = new WpfApp();
                _logger.LogInformation("TrayIconManager: WpfApp instance created.");
                app.ShutdownMode = System.Windows.ShutdownMode.OnExplicitShutdown; // Manage shutdown manually

                // Handle unhandled exceptions on the WPF dispatcher thread
                app.DispatcherUnhandledException += (sender, e) =>
                {
                    _logger.LogError(e.Exception, "TrayIconManager: WpfDispatcherException occurred.");
                    CrashHandler.HandleCrash("WpfDispatcherException", e.Exception);
                    e.Handled = true; // Prevents default crash dialog, but HandleCrash calls Environment.Exit
                };

                WinFormsApp.EnableVisualStyles(); // Enable visual styles for WinForms NotifyIcon
                WinFormsApp.SetCompatibleTextRenderingDefault(false); // For WinForms interop

                                _logger.LogInformation("TrayIconManager: Creating TrayApplicationContext.");
                                _applicationContext = new TrayApplicationContext(_appLifetime, app, _hubMonitorService, _inputService, _shutdownService, _registryService, _settingsService, _hotkeyService, _keyboardHook, _aiCliService, _layoutCaptureService, _projectLauncherService, _logger); // Pass logger
                
                                // Add message filter to route messages to WPF's ComponentDispatcher
                                WinFormsApp.AddMessageFilter(new WpfMessageFilter());
                _logger.LogInformation("TrayIconManager: Starting WinForms message loop.");
                WinFormsApp.Run(_applicationContext); // Start the message pump with our custom context
                _logger.LogInformation("TrayIconManager: WinForms message loop finished.");
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "TrayIconManager: Error in ThreadRun.");
            }
        }

        private class WpfMessageFilter : IMessageFilter
        {
            public bool PreFilterMessage(ref Message m)
            {
                var msg = new System.Windows.Interop.MSG
                {
                    hwnd = m.HWnd,
                    message = m.Msg,
                    wParam = m.WParam,
                    lParam = m.LParam
                };
                return System.Windows.Interop.ComponentDispatcher.RaiseThreadMessage(ref msg);
            }
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation("TrayIconManager: StopAsync called.");
            return Task.CompletedTask;
        }

        public void Dispose()
        {
            _logger.LogInformation("TrayIconManager: Dispose called.");
            _applicationContext?.Dispose();
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
            private readonly HubSettingsService _settingsService;
            private readonly GlobalHotkeyService _hotkeyService;
            private readonly KeyboardHook _keyboardHook;
            private readonly AiCliService _aiCliService;
            private readonly LayoutCaptureService _layoutCaptureService;
            private readonly ProjectLauncherService _projectLauncherService;
            private readonly ILogger _logger;
            private MainWindow _mainWindow;

            public TrayApplicationContext(IHostApplicationLifetime appLifetime, WpfApp wpfApplication, HubMonitorService hubMonitorService, InputService inputService, ShutdownService shutdownService, RegistryService registryService, HubSettingsService settingsService, GlobalHotkeyService hotkeyService, KeyboardHook keyboardHook, AiCliService aiCliService, LayoutCaptureService layoutCaptureService, ProjectLauncherService projectLauncherService, ILogger logger)
            {
                _appLifetime = appLifetime;
                _wpfApplication = wpfApplication; // Store reference to the WPF Application instance
                _hubMonitorService = hubMonitorService; // Assign the injected service
                _inputService = inputService; // Assign the injected service
                _shutdownService = shutdownService;
                _registryService = registryService;
                _settingsService = settingsService;
                _hotkeyService = hotkeyService;
                _keyboardHook = keyboardHook;
                _aiCliService = aiCliService;
                _layoutCaptureService = layoutCaptureService;
                _projectLauncherService = projectLauncherService;
                _logger = logger;
                InitializeComponent();
            }

            private void InitializeComponent()
            {
                try
                {
                    _logger.LogInformation("TrayApplicationContext: Initializing components.");
                    _notifyIcon = new NotifyIcon();
                    // Load the custom icon from the application's directory
                    string iconPath = Path.Combine(AppContext.BaseDirectory, "OmniIcon.ico");
                    if (File.Exists(iconPath))
                    {
                        _logger.LogInformation("TrayApplicationContext: Loading icon from {Path}", iconPath);
                        _notifyIcon.Icon = new Icon(iconPath);
                    }
                    else
                    {
                        _logger.LogWarning("TrayApplicationContext: Icon not found at {Path}, using fallback.", iconPath);
                        _notifyIcon.Icon = SystemIcons.Application; // Fallback to default
                    }
                    _notifyIcon.Text = "OmniSync Hub";
                    _notifyIcon.Visible = true; // Make visible first

                    _logger.LogInformation("TrayApplicationContext: Creating MainWindow.");
                    // Create and store the WPF main window, passing the HubMonitorService
                    _mainWindow = new MainWindow(_hubMonitorService, _inputService, _shutdownService, _registryService, _settingsService, _keyboardHook, _aiCliService, _layoutCaptureService, _projectLauncherService);
                    _logger.LogInformation("TrayApplicationContext: MainWindow created.");

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

                    _hubMonitorService.ExternalCommandReceived += (s, cmd) => {
                        _notifyIcon.ShowBalloonTip(3000, "External Command", $"Executed: {cmd}", ToolTipIcon.Info);
                    };

                    _hotkeyService.OpenHubWindowRequested += (s, e) => OnShowWindow(null, EventArgs.Empty);
                    
                    // CRITICAL: Set the keyboard hook on the UI thread (this thread has the message pump)
                    _logger.LogInformation("TrayApplicationContext: Setting Global Keyboard Hook on UI Thread...");
                    _keyboardHook.SetHook();
                    
                    _logger.LogInformation("TrayApplicationContext: Components initialized.");
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "TrayApplicationContext: Error in InitializeComponent.");
                }
            }

            private void OnMouseClick(object? sender, MouseEventArgs e)
            {
                if (e.Button == MouseButtons.Left && _mainWindow != null)
                {
                    _mainWindow.Dispatcher.BeginInvoke(new Action(() =>
                    {
                        try
                        {
                            if (_mainWindow.IsVisible)
                            {
                                _mainWindow.Hide();
                            }
                            else
                            {
                                _mainWindow.Show();
                                _mainWindow.Activate();
                            }
                        }
                        catch (InvalidOperationException)
                        {
                            // Window might be closed despite our best efforts
                        }
                    }));
                }
            }

            private void OnTestOption(object? sender, EventArgs e)
            {
                // System.Windows.Forms.MessageBox.Show("Test Option clicked!"); // Debug
            }

            private void OnShowWindow(object? sender, EventArgs e)
            {
                if (_mainWindow != null)
                {
                    _mainWindow.Dispatcher.BeginInvoke(new Action(() =>
                    {
                        try
                        {
                            _mainWindow.Show();
                            _mainWindow.Activate();
                        }
                        catch (InvalidOperationException) { }
                    }));
                }
            }

            private void OnHideWindow(object? sender, EventArgs e)
            {
                if (_mainWindow != null)
                {
                    _mainWindow.Dispatcher.BeginInvoke(new Action(() =>
                    {
                        try
                        {
                            _mainWindow.Hide();
                        }
                        catch (InvalidOperationException) { }
                    }));
                }
            }

            private void OnExit(object? sender, EventArgs e)
            {
                _hubMonitorService.AddLogMessage("Exit clicked from tray icon. Forcefully stopping...");
                _notifyIcon.Visible = false; // Hide immediately for better UX
                _notifyIcon.Dispose();
                
                // Initiate standard shutdown
                _appLifetime.StopApplication();
                
                // Force exit after a short delay to ensure the process actually stops
                // even if some services are hanging during cleanup.
                // 300ms is enough for most cleanup tasks to start.
                Task.Run(async () => {
                    await Task.Delay(300);
                    Environment.Exit(0);
                });
            }

            protected override void Dispose(bool disposing)
            {
                Console.WriteLine($"TrayApplicationContext: Dispose called (disposing={disposing})");
                if (disposing)
                {
                    if (_notifyIcon != null)
                    {
                        _notifyIcon.Visible = false;
                        _notifyIcon.Dispose();
                    }
                    
                    try
                    {
                        // Use BeginInvoke to avoid blocking if the UI thread is stuck or busy
                        _wpfApplication.Dispatcher.BeginInvoke(new Action(() =>
                        {
                            if (_mainWindow != null)
                            {
                                _mainWindow.IsInternalClosing = true;
                                _mainWindow.Close(); // Close the WPF window
                            }
                            _wpfApplication.Shutdown(); // Shut down the WPF Application
                        }));
                    }
                    catch (Exception)
                    {
                        // Ignore errors during shutdown
                    }
                }
                base.Dispose(disposing);
                Console.WriteLine("TrayApplicationContext: Calling Application.Exit()");
                System.Windows.Forms.Application.Exit();
            }
        }
    }

}
