using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Models;

namespace OmniSync.Hub.Logic.Services
{
    public class BookProgressService
    {
        private readonly ILogger<BookProgressService> _logger;
        private readonly FileService _fileService;
        private readonly string _progressFilePath;
        private readonly object _lock = new();

        public BookProgressService(ILogger<BookProgressService> logger, FileService fileService)
        {
            _logger = logger;
            _fileService = fileService;
            
            // Store progress in the AppData or a local folder
            var appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var omniFolder = Path.Combine(appData, "OmniSync");
            if (!Directory.Exists(omniFolder)) Directory.CreateDirectory(omniFolder);
            _progressFilePath = Path.Combine(omniFolder, "book_progress.json");
        }

        public async Task<BookProgress?> GetProgressAsync(string bookPath)
        {
            var allProgress = await LoadAllProgressAsync();
            if (allProgress.TryGetValue(bookPath, out var progress))
            {
                return progress;
            }
            return null;
        }

        public async Task SaveProgressAsync(BookProgress progress)
        {
            var allProgress = await LoadAllProgressAsync();
            allProgress[progress.BookPath] = progress;
            await SaveAllProgressAsync(allProgress);
            _logger.LogInformation($"Saved progress for {progress.BookPath} at {progress.Position}");
        }

        private async Task<Dictionary<string, BookProgress>> LoadAllProgressAsync()
        {
            if (!File.Exists(_progressFilePath))
            {
                return new Dictionary<string, BookProgress>();
            }

            try
            {
                var json = await File.ReadAllTextAsync(_progressFilePath);
                return JsonSerializer.Deserialize<Dictionary<string, BookProgress>>(json) ?? new Dictionary<string, BookProgress>();
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error loading book progress.");
                return new Dictionary<string, BookProgress>();
            }
        }

        private async Task SaveAllProgressAsync(Dictionary<string, BookProgress> allProgress)
        {
            try
            {
                var json = JsonSerializer.Serialize(allProgress, new JsonSerializerOptions { WriteIndented = true });
                await File.WriteAllTextAsync(_progressFilePath, json);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error saving book progress.");
            }
        }
    }
}
