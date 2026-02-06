using System;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using System.Runtime.InteropServices;
using System.Windows.Interop;

namespace OmniSync.Hub.Presentation
{
    public partial class OmniSweepWindow : Window
    {
        private readonly OmniSweepViewModel _viewModel;
        private DateTime _ignoreDeactivateUntilUtc = DateTime.MinValue;
        
        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr processId);

        [DllImport("kernel32.dll")]
        private static extern uint GetCurrentThreadId();

        [DllImport("user32.dll")]
        private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        private static extern bool BringWindowToTop(IntPtr hWnd);

        private const int SW_SHOW = 5;

        public OmniSweepWindow(OmniSweepViewModel viewModel)
        {
            InitializeComponent();
            _viewModel = viewModel;
            DataContext = _viewModel;

            _viewModel.RequestClose += (s, e) => Dispatcher.BeginInvoke(new Action(Close));
            
            this.Loaded += (s, e) => {
                _ignoreDeactivateUntilUtc = DateTime.UtcNow.AddMilliseconds(500);
                ForceFocus();
            };

            this.ContentRendered += (s, e) =>
            {
                _ignoreDeactivateUntilUtc = DateTime.UtcNow.AddMilliseconds(500);
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    ForceFocus();
                    // Second attempt after a tiny delay for robustness
                    _ = Task.Delay(50).ContinueWith(_ => Dispatcher.BeginInvoke(new Action(ForceFocus)));
                }), DispatcherPriority.Input);
            };
        }

        private void Window_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
        {
            if (e.Key == Key.Escape)
            {
                Close();
                return;
            }

            if (e.Key == Key.Enter)
            {
                if (ResultsList.SelectedItem == null && ResultsList.Items.Count > 0)
                {
                    ResultsList.SelectedIndex = 0;
                }

                if (ResultsList.SelectedItem != null)
                {
                    _viewModel.ExecuteCommand.Execute(null);
                    e.Handled = true;
                }
                return;
            }

            if (e.Key == Key.Down)
            {
                if (ResultsList.Items.Count == 0)
                {
                    e.Handled = true;
                    return;
                }

                if (ResultsList.SelectedIndex < 0)
                {
                    ResultsList.SelectedIndex = 0;
                }
                else if (ResultsList.SelectedIndex < ResultsList.Items.Count - 1)
                {
                    ResultsList.SelectedIndex++;
                }
                ResultsList.ScrollIntoView(ResultsList.SelectedItem);
                e.Handled = true;
            }
            else if (e.Key == Key.Up)
            {
                if (ResultsList.Items.Count == 0)
                {
                    e.Handled = true;
                    return;
                }

                if (ResultsList.SelectedIndex < 0)
                {
                    ResultsList.SelectedIndex = 0;
                }
                else if (ResultsList.SelectedIndex > 0)
                {
                    ResultsList.SelectedIndex--;
                }
                ResultsList.ScrollIntoView(ResultsList.SelectedItem);
                e.Handled = true;
            }
        }

        private void Window_Activated(object sender, EventArgs e)
        {
            _ignoreDeactivateUntilUtc = DateTime.UtcNow.AddMilliseconds(500);
            ForceFocus();
        }

        private void ForceFocus()
        {
            try
            {
                var hwnd = new WindowInteropHelper(this).Handle;
                if (hwnd == IntPtr.Zero) return;

                // Grab foreground by attaching thread input
                IntPtr foregroundHwnd = GetForegroundWindow();
                uint foregroundThreadId = GetWindowThreadProcessId(foregroundHwnd, IntPtr.Zero);
                uint currentThreadId = GetCurrentThreadId();

                if (foregroundThreadId != currentThreadId)
                {
                    AttachThreadInput(currentThreadId, foregroundThreadId, true);
                    SetForegroundWindow(hwnd);
                    BringWindowToTop(hwnd);
                    ShowWindow(hwnd, SW_SHOW);
                    AttachThreadInput(currentThreadId, foregroundThreadId, false);
                }
                else
                {
                    SetForegroundWindow(hwnd);
                    BringWindowToTop(hwnd);
                    ShowWindow(hwnd, SW_SHOW);
                }

                this.Activate();
                this.Focus();

                SearchInput.Focus();
                Keyboard.Focus(SearchInput);
                SearchInput.SelectAll();
            }
            catch { }
        }

        private void ResultsList_MouseDoubleClick(object sender, MouseButtonEventArgs e)
        {
            if (ResultsList.SelectedItem != null)
            {
                _viewModel.ExecuteCommand.Execute(null);
                e.Handled = true;
            }
        }

        private void Window_Deactivated(object sender, EventArgs e)
        {
            try
            {
                if (DateTime.UtcNow < _ignoreDeactivateUntilUtc)
                {
                    Dispatcher.BeginInvoke(new Action(ForceFocus), DispatcherPriority.Input);
                    return;
                }
                Close();
            }
            catch { }
        }
    }
}
