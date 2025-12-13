using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security;
using OmniSync.Hub.Models; // Add using statement for the new DTO

namespace OmniSync.Hub.Infrastructure.Services
{
    public class FileService
    {
        private readonly string _noteRootPath; // Renamed from _rootPath to clarify its purpose
        private readonly string _browseRootPath; // New field for the configurable browse root

        public FileService()
        {
            // Default constructor, maintains original behavior for _noteRootPath
            _noteRootPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "Obsidian");
            if (!Directory.Exists(_noteRootPath))
            {
                Directory.CreateDirectory(_noteRootPath);
            }
            // Default _browseRootPath to a common starting point, e.g., user's home directory
            _browseRootPath = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        }

        public FileService(string noteRootPath, string browseRootPath)
        {
            _noteRootPath = noteRootPath;
            _browseRootPath = browseRootPath;

            if (!Directory.Exists(_noteRootPath))
            {
                Directory.CreateDirectory(_noteRootPath);
            }
            if (!Directory.Exists(_browseRootPath))
            {
                // Optionally create the browse root if it doesn't exist, or throw an exception.
                // For a browsing context, it's safer to ensure it exists or gracefully handle.
                // For now, let's just ensure the note root exists.
            }
        }

        public string GetNoteRootPath()
        {
            return _noteRootPath;
        }

        public string GetBrowseRootPath()
        {
            return _browseRootPath;
        }

        public string ReadFile(string filePath)
        {
            var fullPath = SanitizeAndGetNoteFullPath(filePath);
            return File.ReadAllText(fullPath);
        }

        public void WriteFile(string filePath, string content)
        {
            var fullPath = SanitizeAndGetNoteFullPath(filePath);
            File.WriteAllText(fullPath, content);
        }

        public void AppendToFile(string filePath, string content)
        {
            var fullPath = SanitizeAndGetNoteFullPath(filePath);
            File.AppendAllText(fullPath, content);
        }

        public IEnumerable<FileSystemEntry> ListDirectoryContents(string relativePath)
        {
            var targetPath = SanitizeAndGetBrowseFullPath(relativePath);

            if (!Directory.Exists(targetPath))
            {
                throw new DirectoryNotFoundException($"Directory not found: {targetPath}");
            }

            var entries = new List<FileSystemEntry>();

            // Add parent directory (..) entry if not at the browse root
            if (!string.Equals(targetPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar), 
                               _browseRootPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar), 
                               StringComparison.OrdinalIgnoreCase))
            {
                entries.Add(new FileSystemEntry
                {
                    Name = "..",
                    Path = Path.GetDirectoryName(relativePath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)) ?? "",
                    IsDirectory = true,
                    Size = 0,
                    LastModified = DateTime.MinValue // Not applicable for parent
                });
            }

            // Directories
            foreach (var dir in Directory.EnumerateDirectories(targetPath))
            {
                var dirInfo = new DirectoryInfo(dir);
                entries.Add(new FileSystemEntry
                {
                    Name = dirInfo.Name,
                    Path = Path.GetRelativePath(_browseRootPath, dir),
                    IsDirectory = true,
                    Size = 0, // Directories don't have a file size
                    LastModified = dirInfo.LastWriteTime
                });
            }

            // Files
            foreach (var file in Directory.EnumerateFiles(targetPath))
            {
                var fileInfo = new FileInfo(file);
                entries.Add(new FileSystemEntry
                {
                    Name = fileInfo.Name,
                    Path = Path.GetRelativePath(_browseRootPath, file),
                    IsDirectory = false,
                    Size = fileInfo.Length,
                    LastModified = fileInfo.LastWriteTime
                });
            }

            return entries.OrderBy(e => e.Name); // Order for consistent display
        }

        public byte[] GetFileChunk(string filePath, long offset, int chunkSize)
        {
            var fullPath = SanitizeAndGetBrowseFullPath(filePath);

            if (!File.Exists(fullPath))
            {
                throw new FileNotFoundException($"File not found: {fullPath}");
            }

            using (var stream = new FileStream(fullPath, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                stream.Seek(offset, SeekOrigin.Begin);
                byte[] buffer = new byte[chunkSize];
                int bytesRead = stream.Read(buffer, 0, chunkSize);

                if (bytesRead < chunkSize)
                {
                    // If less than chunkSize bytes were read, return a truncated array
                    byte[] actualBuffer = new byte[bytesRead];
                    Array.Copy(buffer, actualBuffer, bytesRead);
                    return actualBuffer;
                }
                return buffer;
            }
        }

        // Sanitizes paths for the specific note root (Obsidian directory)
        private string SanitizeAndGetNoteFullPath(string filePath)
        {
            // IMPORTANT: This is a critical security measure to prevent directory traversal attacks.
            var safeFilePath = Path.GetFullPath(Path.Combine(_noteRootPath, filePath));
            if (!safeFilePath.StartsWith(_noteRootPath, StringComparison.OrdinalIgnoreCase))
            {
                throw new SecurityException("Access to the note path is denied.");
            }
            return safeFilePath;
        }

        // Sanitizes paths for the more general browse root
        private string SanitizeAndGetBrowseFullPath(string relativePath)
        {
            var fullPath = Path.GetFullPath(Path.Combine(_browseRootPath, relativePath));

            // Ensure the resolved path is still within the intended browse root
            if (!fullPath.StartsWith(_browseRootPath, StringComparison.OrdinalIgnoreCase))
            {
                throw new SecurityException("Access to the browse path is denied.");
            }
            return fullPath;
        }
    }
}
