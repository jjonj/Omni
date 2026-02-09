using System;
using System.Collections.Generic;
using System.IO;

namespace OmniProjectContext.Services;

public class ContextEngine
{
    private readonly string _rootPath;
    private readonly FileSystemService _fileSystemService;

    public ContextEngine(string rootPath, FileSystemService fileSystemService)
    {
        _rootPath = rootPath;
        _fileSystemService = fileSystemService;
    }

    public List<string> GenerateFileTree()
    {
        var files = new List<string>();
        TraverseDirectory(_rootPath, files);
        return files;
    }

    private void TraverseDirectory(string currentPath, List<string> files)
    {
        try
        {
            foreach (var file in Directory.GetFiles(currentPath))
            {
                // Relative path for the output
                files.Add(Path.GetRelativePath(_rootPath, file));
            }

            foreach (var dir in Directory.GetDirectories(currentPath))
            {
                var dirName = Path.GetFileName(dir);
                if (!_fileSystemService.ShouldIgnoreFolder(dirName))
                {
                    TraverseDirectory(dir, files);
                }
            }
        }
        catch (UnauthorizedAccessException)
        {
            // Skip folders we can't access
        }
    }
}
