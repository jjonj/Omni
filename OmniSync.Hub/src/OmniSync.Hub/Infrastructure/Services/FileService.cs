using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security;
using OmniSync.Hub.Models; // Add using statement for the new DTO
using OmniSync.Hub.Logic.Services; // Added for HubEventSender

namespace OmniSync.Hub.Infrastructure.Services
{
            public class FileService
        {
            private readonly string _noteRootPath; // Renamed from _rootPath to clarify its purpose
            private readonly string _browseRootPath; // New field for the configurable browse root

            // File change watching (Hub -> Android invalidation)
            private readonly Dictionary<string, FileSystemWatcher> _watchers = new();
            private readonly Dictionary<string, DateTime> _lastEventTimes = new();
            private readonly object _watcherLock = new();
            private static readonly TimeSpan EventDebounce = TimeSpan.FromMilliseconds(300);
    
            // Events to notify about file write operations
            public event EventHandler<string>? FileWritten;
            public event EventHandler<string>? BrowseFileWritten;
            public event EventHandler<string>? FileChanged; // New: raised when watched file/dir changes
    
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

        public virtual void DeleteFile(string path)
        {
            if (File.Exists(path))
            {
                File.Delete(path);
                
                // Cleanup thumbnail cache
                try
                {
                    var cachePath = GetThumbnailCachePath(path);
                    if (File.Exists(cachePath)) File.Delete(cachePath);
                }
                catch {}
            }
            else if (Directory.Exists(path))
            {
                Directory.Delete(path, true);
            }
        }

        public virtual void CreateDirectory(string path)
        {
            if (!Directory.Exists(path))
            {
                Directory.CreateDirectory(path);
            }
        }

        private string GetThumbnailCachePath(string ebookPath)
        {
            var appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            var cacheDir = Path.Combine(appData, "OmniSync", "Thumbnails");
            if (!Directory.Exists(cacheDir)) Directory.CreateDirectory(cacheDir);

            // Use hash of path to ensure unique name
            using var md5 = System.Security.Cryptography.MD5.Create();
            var hash = md5.ComputeHash(System.Text.Encoding.UTF8.GetBytes(ebookPath));
            var fileName = BitConverter.ToString(hash).Replace("-", "").ToLower() + ".jpg";
            return Path.Combine(cacheDir, fileName);
        }

        private byte[] CropAndResize(Stream imageStream, int targetWidth, int targetHeight)
        {
            using var original = System.Drawing.Image.FromStream(imageStream);
            
            // Calculate cropping to fit target aspect ratio
            float targetAspect = (float)targetWidth / targetHeight;
            float currentAspect = (float)original.Width / original.Height;

            int cropWidth = original.Width;
            int cropHeight = original.Height;
            int cropX = 0;
            int cropY = 0;

            if (currentAspect > targetAspect)
            {
                cropWidth = (int)(original.Height * targetAspect);
                cropX = (original.Width - cropWidth) / 2;
            }
            else
            {
                cropHeight = (int)(original.Width / targetAspect);
                cropY = (original.Height - cropHeight) / 2;
            }

            using var bitmap = new System.Drawing.Bitmap(targetWidth, targetHeight);
            using var graphics = System.Drawing.Graphics.FromImage(bitmap);
            
            graphics.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
            graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.HighQuality;
            graphics.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
            graphics.CompositingQuality = System.Drawing.Drawing2D.CompositingQuality.HighQuality;

            graphics.DrawImage(original, 
                new System.Drawing.Rectangle(0, 0, targetWidth, targetHeight),
                new System.Drawing.Rectangle(cropX, cropY, cropWidth, cropHeight),
                System.Drawing.GraphicsUnit.Pixel);

            using var ms = new MemoryStream();
            bitmap.Save(ms, System.Drawing.Imaging.ImageFormat.Jpeg);
            return ms.ToArray();
        }

        public virtual void AppendToFile(string path, string content)
        {
            File.AppendAllText(path, content + Environment.NewLine);
        }

        public virtual byte[]? ExtractThumbnail(string ebookPath)
        {
            try
            {
                var cachePath = GetThumbnailCachePath(ebookPath);
                if (File.Exists(cachePath))
                {
                    return File.ReadAllBytes(cachePath);
                }

                var ext = Path.GetExtension(ebookPath).ToLower();
                byte[]? result = null;

                if (ext == ".epub")
                {
                    using var archive = System.IO.Compression.ZipFile.OpenRead(ebookPath);
                    // Find first image in zip (basic heuristic)
                    var image = archive.Entries.FirstOrDefault(e => e.FullName.EndsWith(".jpg") || e.FullName.EndsWith(".jpeg") || e.FullName.EndsWith(".png"));
                    if (image != null)
                    {
                        using var entryStream = image.Open();
                        result = CropAndResize(entryStream, 300, 400);
                    }
                }
                else if (ext == ".pdf")
                {
                    // Using PdfiumViewer to render first page
                    using var document = PdfiumViewer.PdfDocument.Load(ebookPath);
                    if (document.PageCount > 0)
                    {
                        // Render at higher res then crop/resize via our helper for consistent aspect
                        using var image = document.Render(0, 600, 800, true);
                        using var ms = new MemoryStream();
                        image.Save(ms, System.Drawing.Imaging.ImageFormat.Png);
                        ms.Position = 0;
                        result = CropAndResize(ms, 300, 400);
                    }
                }

                if (result != null)
                {
                    File.WriteAllBytes(cachePath, result);
                    return result;
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error extracting thumbnail from {ebookPath}: {ex.Message}");
            }
            return null;
        }

        public virtual string GetNoteRootPath()
        {
            return _noteRootPath;
        }

        public virtual IEnumerable<FileSystemEntry> ScanBooksRecursive(string rootPath)
        {
            Console.WriteLine($"[ScanBooksRecursive] Scanning: {rootPath}");
            if (!Directory.Exists(rootPath))
            {
                Console.WriteLine($"[ScanBooksRecursive] Directory not found: {rootPath}");
                return Enumerable.Empty<FileSystemEntry>();
            }

            var bookExtensions = new[] { ".pdf", ".epub", ".mobi", ".azw3" };
            var audioExtensions = new[] { ".m4b", ".mp3", ".aac", ".opus", ".aax", ".aa" };
            
            var entries = new List<FileSystemEntry>();

            // 1. Process files directly
            var files = Directory.GetFiles(rootPath, "*.*", SearchOption.AllDirectories);
            
            // Track which files we've already handled as part of a "folder book"
            var handledFiles = new HashSet<string>();

            // Detect "Folder Books" (folders containing mostly audio files)
            var allDirs = Directory.GetDirectories(rootPath, "*", SearchOption.AllDirectories);
            foreach (var dir in allDirs)
            {
                var dirFiles = Directory.GetFiles(dir);
                if (dirFiles.Length == 0) continue;

                var audioFiles = dirFiles.Where(f => audioExtensions.Contains(Path.GetExtension(f).ToLower())).ToList();
                
                // If a folder has audio files and they make up the majority of supported files here
                if (audioFiles.Count > 1)
                {
                    var info = new DirectoryInfo(dir);
                    var coverFile = dirFiles.FirstOrDefault(f => 
                        Path.GetFileName(f).ToLower().Equals("cover.jpg") || 
                        Path.GetFileName(f).ToLower().Equals("cover.png") ||
                        Path.GetFileNameWithoutExtension(f).ToLower().Equals(info.Name.ToLower()));

                    entries.Add(new FileSystemEntry
                    {
                        Name = info.Name,
                        Path = info.FullName,
                        IsDirectory = true,
                        EntryType = "AudiobookFolder",
                        Size = audioFiles.Sum(f => new FileInfo(f).Length),
                        LastModified = info.LastWriteTime,
                        Description = coverFile // Reuse Description field for cover path
                    });

                    foreach (var f in dirFiles) handledFiles.Add(f);
                }
            }

            // Process remaining individual files
            foreach (var file in files)
            {
                if (handledFiles.Contains(file)) continue;

                var ext = Path.GetExtension(file).ToLower();
                if (bookExtensions.Contains(ext) || audioExtensions.Contains(ext))
                {
                    var info = new FileInfo(file);
                    
                    // Look for cover in the same folder with same name
                    var dir = Path.GetDirectoryName(file);
                    var coverPath = Path.Combine(dir ?? "", Path.GetFileNameWithoutExtension(file) + ".jpg");
                    if (!File.Exists(coverPath)) coverPath = Path.Combine(dir ?? "", Path.GetFileNameWithoutExtension(file) + ".png");
                    if (!File.Exists(coverPath)) coverPath = Path.Combine(dir ?? "", "cover.jpg");
                    if (!File.Exists(coverPath)) coverPath = Path.Combine(dir ?? "", "cover.png");

                    entries.Add(new FileSystemEntry
                    {
                        Name = info.Name,
                        Path = info.FullName,
                        IsDirectory = false,
                        EntryType = "File",
                        Size = info.Length,
                        LastModified = info.LastWriteTime,
                        Description = File.Exists(coverPath) ? coverPath : null
                    });
                }
            }

            return entries;
        }

        public virtual string GetBrowseRootPath()
        {
            return _browseRootPath;
        }

        public virtual string GetResourcePath(string relativePath)
        {
            // Dev: src/OmniSync.Hub -> Root -> relativePath
            string devPath = Path.Combine(Directory.GetCurrentDirectory(), "..", "..", "..", relativePath);
            if (File.Exists(devPath) || Directory.Exists(devPath)) return Path.GetFullPath(devPath);

            // Prod: bin/Debug/net9.0-windows -> Root -> relativePath
            string prodPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "..", relativePath);
            if (File.Exists(prodPath) || Directory.Exists(prodPath)) return Path.GetFullPath(prodPath);

            return relativePath;
        }

        public virtual string ReadFile(string filePath)
        {
            var fullPath = SanitizeAndGetNoteFullPath(filePath);
            return File.ReadAllText(fullPath);
        }

        public virtual bool WriteFile(string filePath, string content)
        {
            try
            {
                var fullPath = SanitizeAndGetNoteFullPath(filePath);
                File.WriteAllText(fullPath, content);
                FileWritten?.Invoke(this, filePath); // Invoke the event
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }

        public virtual bool WriteBrowseFile(string filePath, string content)
        {
            try
            {
                var fullPath = SanitizeAndGetBrowseFullPath(filePath);
                File.WriteAllText(fullPath, content);
                BrowseFileWritten?.Invoke(this, filePath); // Invoke the event
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }

        public virtual IEnumerable<FileSystemEntry> GetDrives()
        {
            var drives = DriveInfo.GetDrives();
            return drives.Where(d => d.IsReady).Select(d => new FileSystemEntry
            {
                Name = d.Name,      // e.g., "C:\"
                Path = d.Name,      // Absolute path
                IsDirectory = true,
                EntryType = "Drive",
                Size = d.TotalSize,
                LastModified = DateTime.Now 
            });
        }

        public virtual IEnumerable<FileSystemEntry> ListDirectoryContents(string path)
        {
            // If path is empty, and we are in "Whole Computer" mode, return drives.
            if (string.IsNullOrEmpty(path) && string.IsNullOrEmpty(_browseRootPath))
            {
                return GetDrives();
            }

            string targetPath;

            // Determine if we are using relative paths (Sandboxed) or Absolute paths (Full Access)
            if (string.IsNullOrEmpty(_browseRootPath)) 
            {
                // Full Access Mode: Treat input path as absolute
                targetPath = path;
                
                // Safety check: ensure path is valid
                if (string.IsNullOrWhiteSpace(targetPath)) return GetDrives();
            }
            else
            {
                // Sandboxed Mode: Combine with root
                targetPath = SanitizeAndGetBrowseFullPath(path);
            }

            if (!Directory.Exists(targetPath))
            {
                throw new DirectoryNotFoundException($"Directory not found: {targetPath}");
            }

            // Ensure a watcher for this target path (helps Android invalidate caches without polling)
            EnsureWatcherForDirectory(targetPath);

            var entries = new List<FileSystemEntry>();

            // 1. Add Parent Directory (..)
            // We only add '..' if we are not at a Drive Root (e.g., C:\)
            var parent = Directory.GetParent(targetPath);
            if (parent != null)
            {
                entries.Add(new FileSystemEntry
                {
                    Name = "..",
                    Path = parent.FullName,
                    IsDirectory = true,
                    EntryType = "Directory",
                    Size = 0,
                    LastModified = DateTime.MinValue
                });
            }
            else if (string.IsNullOrEmpty(_browseRootPath))
            {
                // If we are at C:\ and in Full Access mode, '..' should probably take us back to the Drive List?
                // For now, let's leave it empty or handle it in client logic.
            }

            try 
            {
                // Directories
                foreach (var dir in Directory.EnumerateDirectories(targetPath))
                {
                    var dirInfo = new DirectoryInfo(dir);
                    // Hide hidden folders
                    if ((dirInfo.Attributes & FileAttributes.Hidden) != 0) continue;

                    entries.Add(new FileSystemEntry
                    {
                        Name = dirInfo.Name,
                        Path = dirInfo.FullName, // Send absolute path for navigation
                        IsDirectory = true,
                        EntryType = "Directory",
                        Size = 0,
                        LastModified = dirInfo.LastWriteTime
                    });
                }

                // Files
                foreach (var file in Directory.EnumerateFiles(targetPath))
                {
                    var fileInfo = new FileInfo(file);
                    if ((fileInfo.Attributes & FileAttributes.Hidden) != 0) continue;

                    entries.Add(new FileSystemEntry
                    {
                        Name = fileInfo.Name,
                        Path = fileInfo.FullName, // Send absolute path
                        IsDirectory = false,
                        EntryType = "File",
                        Size = fileInfo.Length,
                        LastModified = fileInfo.LastWriteTime
                    });
                }
            }
            catch (UnauthorizedAccessException) 
            {
                // Skip system folders we can't read
            }

            return entries.OrderByDescending(e => e.IsDirectory).ThenBy(e => e.Name);
        }

        public IEnumerable<FileSystemEntry> SearchFiles(string path, string query)
        {
            string targetPath = string.IsNullOrEmpty(_browseRootPath) ? path : SanitizeAndGetBrowseFullPath(path);

            if (!Directory.Exists(targetPath))
            {
                throw new DirectoryNotFoundException($"Directory not found: {targetPath}");
            }

            var results = new List<FileSystemEntry>();
            SearchRecursive(targetPath, query, results, 100);
            return results;
        }

        public FileSystemEntry GetFileInfo(string path)
        {
            string targetPath = string.IsNullOrEmpty(_browseRootPath) ? path : SanitizeAndGetBrowseFullPath(path);

            if (File.Exists(targetPath))
            {
                var fileInfo = new FileInfo(targetPath);
                return new FileSystemEntry
                {
                    Name = fileInfo.Name,
                    Path = fileInfo.FullName,
                    IsDirectory = false,
                    EntryType = "File",
                    Size = fileInfo.Length,
                    LastModified = fileInfo.LastWriteTime
                };
            }
            else if (Directory.Exists(targetPath))
            {
                var dirInfo = new DirectoryInfo(targetPath);
                return new FileSystemEntry
                {
                    Name = dirInfo.Name,
                    Path = dirInfo.FullName,
                    IsDirectory = true,
                    EntryType = "Directory",
                    Size = 0,
                    LastModified = dirInfo.LastWriteTime
                };
            }
            
            throw new FileNotFoundException($"File or directory not found: {targetPath}");
        }

        private void SearchRecursive(string directory, string query, List<FileSystemEntry> results, int maxResults)
        {
            if (results.Count >= maxResults) return;

            try
            {
                foreach (var file in Directory.EnumerateFiles(directory))
                {
                    if (file.Contains(query, StringComparison.OrdinalIgnoreCase))
                    {
                        var fileInfo = new FileInfo(file);
                        if ((fileInfo.Attributes & FileAttributes.Hidden) != 0) continue;

                        results.Add(new FileSystemEntry
                        {
                            Name = fileInfo.Name,
                            Path = fileInfo.FullName,
                            IsDirectory = false,
                            EntryType = "File",
                            Size = fileInfo.Length,
                            LastModified = fileInfo.LastWriteTime
                        });

                        if (results.Count >= maxResults) return;
                    }
                }

                foreach (var dir in Directory.EnumerateDirectories(directory))
                {
                    SearchRecursive(dir, query, results, maxResults);
                    if (results.Count >= maxResults) return;
                }
            }
            catch (UnauthorizedAccessException) { }
            catch (IOException) { }
            catch (SecurityException) { }
        }

        public string? FindExecutable(string filename)
        {
            if (File.Exists(filename)) return Path.GetFullPath(filename);

            var extensions = new[] { "", ".exe", ".bat", ".py", ".ps1" };
            var searchRoots = new List<string>();

            // 1. Add specific tool directory
            if (Directory.Exists(@"B:\Gdrive\tools")) searchRoots.Add(@"B:\Gdrive\tools");

            // 2. Add Program Files on all fixed drives
            foreach (var drive in DriveInfo.GetDrives().Where(d => d.IsReady && d.DriveType == DriveType.Fixed))
            {
                var pf = Path.Combine(drive.Name, "Program Files");
                var pf86 = Path.Combine(drive.Name, "Program Files (x86)");
                if (Directory.Exists(pf)) searchRoots.Add(pf);
                if (Directory.Exists(pf86)) searchRoots.Add(pf86);
            }

            foreach (var root in searchRoots)
            {
                try
                {
                    var stack = new Stack<string>();
                    stack.Push(root);

                    while (stack.Count > 0)
                    {
                        var dir = stack.Pop();
                        try
                        {
                            foreach (var ext in extensions)
                            {
                                var target = filename.EndsWith(ext, StringComparison.OrdinalIgnoreCase) ? filename : filename + ext;
                                var files = Directory.EnumerateFiles(dir, target);
                                var first = files.FirstOrDefault();
                                if (first != null) return first;
                            }
                            
                            foreach (var subDir in Directory.EnumerateDirectories(dir))
                            {
                                stack.Push(subDir);
                            }
                        }
                        catch (UnauthorizedAccessException) { }
                        catch (Exception) { }
                    }
                }
                catch (Exception) { }
            }
            return null;
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

        public bool DeleteEntry(string path)
        {
            try
            {
                string targetPath = string.IsNullOrEmpty(_browseRootPath) ? path : SanitizeAndGetBrowseFullPath(path);
                DeleteFile(targetPath);
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }

        public bool CopyEntry(string sourcePath, string destPath)
        {
            try
            {
                string source = string.IsNullOrEmpty(_browseRootPath) ? sourcePath : SanitizeAndGetBrowseFullPath(sourcePath);
                string dest = string.IsNullOrEmpty(_browseRootPath) ? destPath : SanitizeAndGetBrowseFullPath(destPath);

                if (File.Exists(source))
                {
                    File.Copy(source, dest, true);
                    return true;
                }
                if (Directory.Exists(source))
                {
                    CopyDirectory(source, dest);
                    return true;
                }
                return false;
            }
            catch (Exception)
            {
                return false;
            }
        }

        public bool MoveEntry(string sourcePath, string destPath)
        {
            try
            {
                string source = string.IsNullOrEmpty(_browseRootPath) ? sourcePath : SanitizeAndGetBrowseFullPath(sourcePath);
                string dest = string.IsNullOrEmpty(_browseRootPath) ? destPath : SanitizeAndGetBrowseFullPath(destPath);

                if (File.Exists(source))
                {
                    if (File.Exists(dest)) File.Delete(dest);
                    File.Move(source, dest);
                    return true;
                }
                if (Directory.Exists(source))
                {
                    if (Directory.Exists(dest)) Directory.Delete(dest, true);
                    Directory.Move(source, dest);
                    return true;
                }
                return false;
            }
            catch (Exception)
            {
                return false;
            }
        }

        private static void CopyDirectory(string sourceDir, string destinationDir)
        {
            var dir = new DirectoryInfo(sourceDir);
            if (!dir.Exists) throw new DirectoryNotFoundException($"Source directory not found: {dir.FullName}");

            Directory.CreateDirectory(destinationDir);

            foreach (FileInfo file in dir.GetFiles())
            {
                string targetFilePath = Path.Combine(destinationDir, file.Name);
                file.CopyTo(targetFilePath);
            }

            foreach (DirectoryInfo subDir in dir.GetDirectories())
            {
                string newDestinationDir = Path.Combine(destinationDir, subDir.Name);
                CopyDirectory(subDir.FullName, newDestinationDir);
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

        // Create and manage a FileSystemWatcher for a directory path (absolute)
        public void EnsureWatcherForDirectory(string absoluteDirectory)
        {
            if (string.IsNullOrWhiteSpace(absoluteDirectory) || !Directory.Exists(absoluteDirectory)) return;

            lock (_watcherLock)
            {
                if (_watchers.ContainsKey(absoluteDirectory)) return;

                var fsw = new FileSystemWatcher(absoluteDirectory)
                {
                    IncludeSubdirectories = false,
                    NotifyFilter = NotifyFilters.FileName | NotifyFilters.DirectoryName | NotifyFilters.LastWrite | NotifyFilters.Size
                };

                FileSystemEventHandler onChanged = (s, e) => HandleFsEvent(e.FullPath);
                RenamedEventHandler onRenamed = (s, e) =>
                {
                    HandleFsEvent(e.FullPath);
                    if (!string.IsNullOrEmpty(e.OldFullPath)) HandleFsEvent(e.OldFullPath);
                };

                fsw.Created += onChanged;
                fsw.Changed += onChanged;
                fsw.Deleted += onChanged;
                fsw.Renamed += onRenamed;
                fsw.EnableRaisingEvents = true;

                _watchers[absoluteDirectory] = fsw;
            }
        }

        private void HandleFsEvent(string fullPath)
        {
            try
            {
                lock (_watcherLock)
                {
                    var now = DateTime.UtcNow;
                    if (_lastEventTimes.TryGetValue(fullPath, out var last) && (now - last) < EventDebounce)
                    {
                        return; // debounce
                    }
                    _lastEventTimes[fullPath] = now;
                }

                // Emit event with relative or absolute? We send absolute so Android can match its entries
                FileChanged?.Invoke(this, fullPath);
            }
            catch { }
        }
    }
}
