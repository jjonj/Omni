using Moq;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic;
using OmniSync.Hub.Presentation;
using System;
using System.Collections.Generic;
using System.Linq;
using Xunit;

namespace OmniSync.Hub.Tests.Presentation
{
    public class ProjectSelectorViewModelTests
    {
        private readonly Mock<HubSettingsService> _mockSettingsService;
        private readonly Mock<ProjectLauncherService> _mockProjectLauncherService;

        public ProjectSelectorViewModelTests()
        {
            _mockSettingsService = new Mock<HubSettingsService>(null);
            _mockProjectLauncherService = new Mock<ProjectLauncherService>(null, null);

            var settings = new HubSettings
            {
                Projects = new List<Project>
                {
                    new Project { Id = Guid.NewGuid(), Name = "Project 1" },
                    new Project { Id = Guid.NewGuid(), Name = "Project 2" }
                }
            };
            _mockSettingsService.SetupGet(s => s.Settings).Returns(settings);
        }

        [Fact]
        public void Constructor_ShouldPopulateProjects()
        {
            // Act
            var vm = new ProjectSelectorViewModel(_mockSettingsService.Object, _mockProjectLauncherService.Object);

            // Assert
            Assert.Equal(2, vm.Projects.Count);
            Assert.Equal("Project 1", vm.Projects[0].Name);
            Assert.Equal("Project 2", vm.Projects[1].Name);
        }

        [Fact]
        public void LaunchProjectCommand_ShouldCallLauncherService()
        {
            // Arrange
            var vm = new ProjectSelectorViewModel(_mockSettingsService.Object, _mockProjectLauncherService.Object);
            var project = vm.Projects[0];

            // Act
            vm.LaunchProjectCommand.Execute(project);

            // Assert
            _mockProjectLauncherService.Verify(s => s.LaunchProject(project), Times.Once);
        }
    }
}