using System;
using System.Windows;
using System.Windows.Input;

namespace OmniSync.Hub.Presentation
{
    public partial class ProjectSelectorWindow : Window
    {
        private readonly ProjectSelectorViewModel _viewModel;

        public ProjectSelectorWindow(ProjectSelectorViewModel viewModel)
        {
            InitializeComponent();
            _viewModel = viewModel;
            DataContext = _viewModel;

            _viewModel.RequestClose += (s, e) => Dispatcher.BeginInvoke(new Action(Close));
            
            this.KeyDown += OnKeyDown;
            this.Deactivated += (s, e) => Close(); // Close if focus lost
        }

        private void OnKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
        {
            if (e.Key == Key.Escape)
            {
                Close();
                return;
            }

            if (e.Key >= Key.D1 && e.Key <= Key.D9)
            {
                int index = e.Key - Key.D1;
                LaunchProjectByIndex(index);
            }
            else if (e.Key >= Key.NumPad1 && e.Key <= Key.NumPad9)
            {
                int index = e.Key - Key.NumPad1;
                LaunchProjectByIndex(index);
            }
        }

        private void LaunchProjectByIndex(int index)
        {
            if (index >= 0 && index < _viewModel.Projects.Count)
            {
                var project = _viewModel.Projects[index];
                _viewModel.LaunchProjectCommand.Execute(project);
            }
        }
    }
}