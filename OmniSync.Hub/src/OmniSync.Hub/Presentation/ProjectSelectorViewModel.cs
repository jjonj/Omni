using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;
using System.Windows.Threading;
using System.Threading.Tasks;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic;
using OmniSync.Hub.Infrastructure;

namespace OmniSync.Hub.Presentation
{
    public class ProjectSelectorViewModel : INotifyPropertyChanged
    {
        private readonly HubSettingsService _settingsService;
        private readonly ProjectLauncherService _projectLauncherService;
        private readonly DispatcherTimer _closeTimer;

        public ObservableCollection<Project> Projects { get; }

        public ICommand LaunchProjectCommand { get; }
        
        public event EventHandler? RequestClose;

        public ProjectSelectorViewModel(HubSettingsService settingsService, ProjectLauncherService projectLauncherService)
        {
            _settingsService = settingsService;
            _projectLauncherService = projectLauncherService;

            Projects = new ObservableCollection<Project>(_settingsService.Settings.Projects ?? new System.Collections.Generic.List<Project>());
            LaunchProjectCommand = new RelayCommand(async p => await ExecuteLaunchProject(p as Project));

            _closeTimer = new DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(2)
            };
            _closeTimer.Tick += (s, e) => 
            {
                _closeTimer.Stop();
                RequestClose?.Invoke(this, EventArgs.Empty);
            };
        }

        private async Task ExecuteLaunchProject(Project? project)
        {
            if (project == null) return;
            
            // Launch the project
            await _projectLauncherService.LaunchProject(project);

            // Start or reset the auto-close timer
            _closeTimer.Stop();
            _closeTimer.Start();
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }
}
