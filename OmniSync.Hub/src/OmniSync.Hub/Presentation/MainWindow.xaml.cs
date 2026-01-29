using System.Windows;
using System.Collections.ObjectModel; // Still needed for ObservableCollection type reference in XAML binding
using System.Collections.Specialized;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Threading; // For Dispatcher
using OmniSync.Hub.Logic.Monitoring; // For HubMonitorService
using System; // For Environment.NewLine
using System.Linq;
using OmniSync.Hub.Infrastructure.Services; // Add this using directive
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Logic;
using Microsoft.Win32; // Added for Registry access
using System.Collections.Generic; // For KeyValuePair
using System.Windows.Controls; // For Button
using System.Windows.Input; // Added for MouseButtonEventArgs
using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Windows.Data;

namespace OmniSync.Hub.Presentation
{
    public partial class MainWindow : Window
    {
        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

        private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

        private readonly HubMonitorService _hubMonitorService;
        private readonly RegistryService _registryService;
        private readonly HubSettingsService _settingsService;
        private readonly MainViewModel _viewModel;

        public bool IsInternalClosing { get; set; } = false;

        public MainWindow(HubMonitorService hubMonitorService, InputService inputService, ProcessService processService, ShutdownService shutdownService, RegistryService registryService, HubSettingsService settingsService, KeyboardHook keyboardHook, AiCliService aiCliService, LayoutCaptureService layoutCaptureService, ProjectLauncherService projectLauncherService)
        {
            InitializeComponent();
            EnableDarkModeTitleBar();
            
            _hubMonitorService = hubMonitorService;
            _registryService = registryService;
            _settingsService = settingsService;

            _viewModel = new MainViewModel(hubMonitorService, inputService, processService, shutdownService, registryService, settingsService, keyboardHook, aiCliService, layoutCaptureService, projectLauncherService);
            DataContext = _viewModel;

            // Hook up event handlers (now in ViewModel where appropriate)
            _settingsService.SettingsChanged += OnSettingsChanged;
            inputService.ModifierStateChanged += OnModifierStateChanged;
        }

        private void LogTextBox_TextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
        {
            LogTextBox.ScrollToEnd();
        }

        private void OnSettingsChanged(object? s, EventArgs e)
        {
            Dispatcher?.Invoke(() => _viewModel.RefreshMappingsGrid());
        }

        private void OnModifierStateChanged(object? s, ModifierStateEventArgs e)
        {
            Dispatcher?.Invoke(() => _viewModel.UpdateModifierState(e.Modifier, e.IsPressed));
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            if (IsInternalClosing)
            {
                // Cleanup to prevent memory leaks and crashes on exit
                _settingsService.SettingsChanged -= OnSettingsChanged;
                // Note: ModifierStateChanged is on a singleton service, but we should still cleanup
                
                base.OnClosing(e);
                return;
            }

            // Prevent the window from actually closing. Instead, just hide it.
            e.Cancel = true; 
            this.Hide();
        }

        // XAML Event Handlers (delegating to ViewModel where needed for complex interactions)
        private void RunOnStartupCheckBox_Checked(object sender, RoutedEventArgs e) => _viewModel.IsRunOnStartupEnabled = true;
        private void RunOnStartupCheckBox_Unchecked(object sender, RoutedEventArgs e) => _viewModel.IsRunOnStartupEnabled = false;

        // Long press for shutdown button remains in code-behind to handle MouseDown/Up events properly
        private void ShutdownButton_MouseDown(object sender, MouseButtonEventArgs e)
        {
            _viewModel.StartLongPressTimer();
            e.Handled = true; // Prevent default button behavior
        }

        private void ShutdownButton_MouseUp(object sender, MouseButtonEventArgs e)
        {
            _viewModel.StopLongPressTimer();
            e.Handled = true;
        }

        private void TextBox_GotFocus(object sender, RoutedEventArgs e)
        {
            if (sender is System.Windows.Controls.TextBox textBox)
            {
                textBox.SelectAll();
            }
        }

        private void Expander_Expanded(object sender, RoutedEventArgs e)
        {
            if (sender is Expander exp && exp.DataContext is CollectionViewGroup group)
            {
                _viewModel.ToggleCategoryExpansionCommand.Execute(new object[] { group.Name.ToString() ?? "", true });
            }
        }

        private void Expander_Collapsed(object sender, RoutedEventArgs e)
        {
            if (sender is Expander exp && exp.DataContext is CollectionViewGroup group)
            {
                _viewModel.ToggleCategoryExpansionCommand.Execute(new object[] { group.Name.ToString() ?? "", false });
            }
        }

        private void EnableDarkModeTitleBar()
        {
            var helper = new WindowInteropHelper(this);
            helper.EnsureHandle();
            if (helper.Handle == IntPtr.Zero) return;
            
            int useImmersiveDarkMode = 1;
            DwmSetWindowAttribute(helper.Handle, DWMWA_USE_IMMERSIVE_DARK_MODE, ref useImmersiveDarkMode, sizeof(int));
        }
    }
}
