using System;
using System.Collections.Generic;
using System.Linq;

namespace OmniProjectContext.Services;

public class FileSystemService
{
    private static readonly HashSet<string> ExplicitlyIgnored = new(StringComparer.OrdinalIgnoreCase)
    {
        "conductor", "docs", "build", "bin", "obj", "Assets", "Resources"
    };

    private static readonly HashSet<string> AutoIgnored = new(StringComparer.OrdinalIgnoreCase)
    {
        "node_modules", "dist", "out", "target", "vendor", "venv", ".venv", "__pycache__",
        ".gradle", ".idea", ".vs", ".vscode", "debug", "release", "temp", "tmp", "logs",
        "test-results", "coverage", "publish", "screenshots", "videos", "archives",
        "packages", "extern"
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

        return false;
    }
}
