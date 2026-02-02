using System;
using System.Windows;
using System.Windows.Input;

namespace OmniSync.Hub.Presentation
{
    public partial class OmniSweepWindow : Window
    {
        private readonly OmniSweepViewModel _viewModel;

        public OmniSweepWindow(OmniSweepViewModel viewModel)
        {
            InitializeComponent();
            _viewModel = viewModel;
            DataContext = _viewModel;

            _viewModel.RequestClose += (s, e) => Dispatcher.BeginInvoke(new Action(Close));
            
            this.Loaded += (s, e) => {
                SearchInput.Focus();
            };
        }

        private void SearchInput_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
        {
            if (e.Key == Key.Escape)
            {
                Close();
                return;
            }

            if (e.Key == Key.Enter)
            {
                _viewModel.ExecuteCommand.Execute(null);
                e.Handled = true;
                return;
            }

            if (e.Key == Key.Down)
            {
                if (ResultsList.SelectedIndex < ResultsList.Items.Count - 1)
                {
                    ResultsList.SelectedIndex++;
                    ResultsList.ScrollIntoView(ResultsList.SelectedItem);
                }
                e.Handled = true;
            }
            else if (e.Key == Key.Up)
            {
                if (ResultsList.SelectedIndex > 0)
                {
                    ResultsList.SelectedIndex--;
                    ResultsList.ScrollIntoView(ResultsList.SelectedItem);
                }
                e.Handled = true;
            }
        }

        private void Window_Deactivated(object sender, EventArgs e)
        {
            try { Close(); } catch { }
        }
    }
}
