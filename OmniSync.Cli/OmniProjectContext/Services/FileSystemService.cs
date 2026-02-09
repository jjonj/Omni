using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace OmniProjectContext.Services;

public class FileSystemService
{
    private static readonly HashSet<string> ExplicitlyIgnored = new(StringComparer.OrdinalIgnoreCase)
    {
        "conductor", "docs", "build", "bin", "obj", "Assets", "Resources", "gradle"
    };

    private static readonly HashSet<string> AutoIgnored = new(StringComparer.OrdinalIgnoreCase)
    {
        "node_modules", "dist", "out", "target", "vendor", "venv", ".venv", "__pycache__",
        ".gradle", ".idea", ".vs", ".vscode", "debug", "release", "temp", "tmp", "logs",
        "test-results", "coverage", "publish", "screenshots", "videos", "archives",
        "packages", "extern"
    };

    private static readonly HashSet<string> HighValueExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".cs", ".py", ".js", ".ts", ".html", ".css", ".md", ".txt", ".json", 
        ".xml", ".xaml", ".kt", ".kts", ".java", ".sh", ".ps1", ".bat", ".csproj", ".sln"
    };

    public bool ShouldIgnoreFolder(string folderName)
    {
        if (string.Equals(folderName, ".omni", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (folderName.StartsWith(".") && folderName.Length > 1)
        {
            return true;
        }

        if (ExplicitlyIgnored.Contains(folderName))
        {
            return true;
        }

        if (AutoIgnored.Contains(folderName))
        {
            return true;
        }

        // Catch versioned folders like gradle-8.8
        if (folderName.StartsWith("gradle-", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        return false;
    }

    public bool IsHighValueFile(string filePath)
    {
        var ext = Path.GetExtension(filePath);
        return HighValueExtensions.Contains(ext);
    }
}
