using Xunit;
using OmniSync.Hub.Logic.Services;
using System.IO;

namespace OmniSync.Hub.Tests.Logic.Services
{
    public class AssistantServiceTests
    {
        [Fact]
        public void PathDiscovery_ShouldIdentifyAthenaPaths()
        {
            // Note: In a real test we'd mock the filesystem, 
            // but for this project we often test against the real layout 
            // or well-known relative paths.
            
            var service = new AssistantService();
            var paths = service.GetAthenaPaths();
            
            Assert.True(Directory.Exists(paths.AthenaRoot), $"AthenaRoot not found at {paths.AthenaRoot}");
            Assert.True(Directory.Exists(paths.AthenaSrc), $"AthenaSrc not found at {paths.AthenaSrc}");
            Assert.True(File.Exists(paths.PythonExecutable), $"PythonExecutable not found at {paths.PythonExecutable}");
        }
    }
}
