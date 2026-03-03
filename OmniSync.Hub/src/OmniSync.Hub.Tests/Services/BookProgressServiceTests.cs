using Moq;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Models;
using Xunit;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Tests.Services
{
    public class BookProgressServiceTests
    {
        private readonly Mock<ILogger<BookProgressService>> _loggerMock;
        private readonly Mock<FileService> _fileServiceMock;
        private readonly BookProgressService _service;

        public BookProgressServiceTests()
        {
            _loggerMock = new Mock<ILogger<BookProgressService>>();
            _fileServiceMock = new Mock<FileService>();
            _service = new BookProgressService(_loggerMock.Object, _fileServiceMock.Object);
        }

        [Fact]
        public async Task SaveProgressAsync_ShouldStoreProgress()
        {
            // Arrange
            var progress = new BookProgress 
            { 
                BookPath = @"B:\GDrive\Books\Book\Fiction\Test.epub", 
                Position = "10", 
                LastUpdated = System.DateTime.UtcNow 
            };

            // Act
            await _service.SaveProgressAsync(progress);
            var retrieved = await _service.GetProgressAsync(progress.BookPath);

            // Assert
            Assert.NotNull(retrieved);
            Assert.Equal(progress.Position, retrieved.Position);
        }

        [Fact]
        public async Task GetProgressAsync_ShouldReturnNullForNewBook()
        {
            // Act
            var retrieved = await _service.GetProgressAsync("non-existent-path");

            // Assert
            Assert.Null(retrieved);
        }
    }
}
