using System;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;
using System.Linq;

namespace OmniProjectContext.Services;

public class SkeletonExtractor
{
    private static readonly HashSet<string> IgnoredNamespaces = new(StringComparer.OrdinalIgnoreCase)
    {
        "System", "System.Collections.Generic", "System.Linq", "System.Text", "System.Threading", "System.IO", "System.Diagnostics",
        "System.Windows", "System.Windows.Input", "System.Windows.Data", "System.Windows.Controls", "System.Windows.Threading",
        "System.Collections.ObjectModel", "System.ComponentModel", "System.Runtime.CompilerServices", "System.Runtime.InteropServices",
        "System.Collections.Concurrent", "System.Globalization", "System.Net.Http", "System.Security",
        "Microsoft.Extensions.Logging", "Microsoft.Extensions.Configuration", "Microsoft.Extensions.Hosting",
        "Microsoft.Extensions.DependencyInjection", "Microsoft.AspNetCore.Mvc", "Microsoft.AspNetCore.Builder",
        "Microsoft.AspNetCore.SignalR", "Microsoft.AspNetCore.StaticFiles", "Xunit", "Moq", "Newtonsoft.Json"
    };

    private static readonly HashSet<string> IgnoredPythonModules = new(StringComparer.OrdinalIgnoreCase)
    {
        "os", "sys", "time", "subprocess", "shutil", "json", "argparse", "asyncio", "logging", "math", "re", 
        "pathlib", "ctypes", "threading", "queue", "traceback", "uuid", "socket", "datetime", "struct", "pickle", "glob"
    };

    public string Extract(string content, string extension)
    {
        if (string.IsNullOrWhiteSpace(content)) return string.Empty;

        return extension.ToLower() switch
        {
            ".cs" => ExtractCSharp(content),
            ".py" => ExtractPython(content),
            _ => string.Empty
        };
    }

    public List<string> GetImports(string content, string extension)
    {
        if (string.IsNullOrWhiteSpace(content)) return new List<string>();

        var imports = extension.ToLower() switch
        {
            ".cs" => GetCSharpImports(content),
            ".py" => GetPythonImports(content),
            _ => new List<string>()
        };

        return imports
            .Select(i => i.Replace("\r", "").Replace("\n", "").Trim())
            .Where(i => !string.IsNullOrWhiteSpace(i))
            .Distinct()
            .ToList();
    }

    private string ExtractCSharp(string content)
    {
        var sb = new StringBuilder();
        var lines = content.Split(new[] { "\r\n", "\n" }, StringSplitOptions.None);
        // Only include high-level structural declarations (namespace, class, interface, enum, struct)
        var structureKeywords = new[] { "namespace ", "class ", "interface ", "enum ", "struct " };

        foreach (var line in lines)
        {
            var trimmedLine = line.Trim();
            if (string.IsNullOrWhiteSpace(trimmedLine) || trimmedLine.StartsWith("//")) continue;

            if (structureKeywords.Any(kw => trimmedLine.StartsWith(kw) || trimmedLine.Contains(" " + kw)))
            {
                // Ensure it's a declaration, not a method, property, or assignment
                if (!trimmedLine.Contains("(") && !trimmedLine.Contains(")") && 
                    !trimmedLine.Contains("{ get;") && !trimmedLine.Contains(" = ") &&
                    !trimmedLine.Contains("return ") && !trimmedLine.Contains("=>"))
                {
                    // Clean up trailing structural characters
                    var cleanLine = trimmedLine.TrimEnd(' ', '{', ';');
                    if (!string.IsNullOrWhiteSpace(cleanLine))
                    {
                        sb.Append(cleanLine).Append("¶");
                    }
                }
            }
        }
        return sb.ToString().TrimEnd('¶');
    }

    private List<string> GetCSharpImports(string content)
    {
        return new List<string>(); // Imports no longer wanted
    }

    private string ExtractPython(string content)
    {
        var sb = new StringBuilder();
        var lines = content.Split(new[] { "\r\n", "\n" }, StringSplitOptions.None);
        foreach (var line in lines)
        {
            var trimmed = line.Trim();
            // Only include class declarations for Python, remove all function defs
            if (trimmed.StartsWith("class ") && trimmed.EndsWith(":"))
            {
                sb.Append(trimmed.TrimEnd(':')).Append("¶");
            }
        }
        return sb.ToString().TrimEnd('¶');
    }

    private List<string> GetPythonImports(string content)
    {
        return new List<string>(); // Imports no longer wanted
    }
}
