using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Models;
using Xunit;
using System.IO;
using System.Linq;
using System.Collections.Generic;

namespace OmniSync.Hub.Tests.Infrastructure
{
    public class FileServiceBookTests
    {
        private readonly FileService _service;
        private readonly string _testTempPath;

        public FileServiceBookTests()
        {
            _service = new FileService();
            _testTempPath = Path.Combine(Path.GetTempPath(), "OmniSyncTests_Books");
            if (Directory.Exists(_testTempPath)) Directory.Delete(_testTempPath, true);
            Directory.CreateDirectory(_testTempPath);
        }

        [Fact]
        public void ScanBooksRecursive_ShouldFindBooksInSubfolders()
        {
            // Arrange
            var subDir = Path.Combine(_testTempPath, "Fiction");
            Directory.CreateDirectory(subDir);
            
            var ebookPath = Path.Combine(subDir, "test.epub");
            File.WriteAllText(ebookPath, "fake content");
            
            var audioDir = Path.Combine(_testTempPath, "Audio");
            Directory.CreateDirectory(audioDir);
            var audioPath = Path.Combine(audioDir, "test.m4b");
            File.WriteAllText(audioPath, "fake audio");

            // Act
            var results = _service.ScanBooksRecursive(_testTempPath).ToList();

            // Assert
            Assert.Equal(2, results.Count);
            Assert.Contains(results, r => r.Name == "test.epub");
            Assert.Contains(results, r => r.Name == "test.m4b");
        }

        [Fact]
        public void ScanBooksRecursive_ShouldIgnoreNonBookFiles()
        {
            // Arrange
            var txtPath = Path.Combine(_testTempPath, "ignore.txt");
            File.WriteAllText(txtPath, "ignore me");

            // Act
            var results = _service.ScanBooksRecursive(_testTempPath).ToList();

            // Assert
            Assert.Empty(results);
        }
    }
}
