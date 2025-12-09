using System;
using System.IO;
using System.Security;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class FileService
    {
        private readonly string _rootPath;

        public FileService()
        {
            // As per design, using a hardcoded root path.
            _rootPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "Obsidian");
            if (!Directory.Exists(_rootPath))
            {
                Directory.CreateDirectory(_rootPath);
            }
        }

        public string GetRootPath()
        {
            return _rootPath;
        }

        public string ReadFile(string filePath)
        {
            var fullPath = SanitizeAndGetFullPath(filePath);
            return File.ReadAllText(fullPath);
        }

        public void AppendToFile(string filePath, string content)
        {
            var fullPath = SanitizeAndGetFullPath(filePath);
            File.AppendAllText(fullPath, content);
        }

        public void WriteFile(string filePath, string content)
        {
            var fullPath = SanitizeAndGetFullPath(filePath);
            File.WriteAllText(fullPath, content);
        }

        private string SanitizeAndGetFullPath(string filePath)
        {
            // IMPORTANT: This is a critical security measure to prevent directory traversal attacks.
            var safeFilePath = Path.GetFullPath(Path.Combine(_rootPath, filePath));
            if (!safeFilePath.StartsWith(_rootPath, StringComparison.OrdinalIgnoreCase))
            {
                throw new SecurityException("Access to the path is denied.");
            }
            return safeFilePath;
        }
    }
}
