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
        var signatureRegex = new Regex(@"^\s*(public|private|protected|internal|static|class|interface|namespace|enum|struct|void|string|int|bool|Task|async).*", RegexOptions.Compiled);

        foreach (var line in lines)
        {
            var trimmedStart = line.TrimStart();
            if (signatureRegex.IsMatch(trimmedStart) && !trimmedStart.StartsWith("//"))
            {
                if (!trimmedStart.Contains(" = ") && !trimmedStart.StartsWith("return ") && !trimmedStart.StartsWith("throw "))
                {
                    sb.AppendLine(line.TrimEnd());
                }
            }
        }
        return sb.ToString();
    }

    private List<string> GetCSharpImports(string content)
    {
        var imports = new List<string>();
        var matches = Regex.Matches(content, @"^\s*using\s+([\w\.]+);", RegexOptions.Multiline);
        foreach (Match match in matches)
        {
            var imp = match.Groups[1].Value;
            if (IgnoredNamespaces.Any(n => imp.StartsWith(n, StringComparison.OrdinalIgnoreCase))) continue;

            var parts = imp.Split('.');
            imports.Add(parts.Last());
        }
        return imports;
    }

    private string ExtractPython(string content)
    {
        var sb = new StringBuilder();
        var lines = content.Split(new[] { "\r\n", "\n" }, StringSplitOptions.None);
        foreach (var line in lines)
        {
            var trimmed = line.Trim();
            if ((trimmed.StartsWith("class ") || trimmed.StartsWith("def ")) && trimmed.EndsWith(":"))
            {
                sb.AppendLine(line.TrimEnd());
            }
        }
        return sb.ToString();
    }

    private List<string> GetPythonImports(string content)
    {
        var imports = new List<string>();
        var matches = Regex.Matches(content, @"^\s*(?:from\s+([\w\.]+)\s+import|import\s+([\w\.]+))", RegexOptions.Multiline);
        foreach (Match match in matches)
        {
            string imp = "";
            if (match.Groups[1].Success) imp = match.Groups[1].Value;
            else if (match.Groups[2].Success) imp = match.Groups[2].Value;

            if (!string.IsNullOrEmpty(imp))
            {
                if (IgnoredPythonModules.Contains(imp)) continue;
                var parts = imp.Split('.');
                imports.Add(parts.Last());
            }
        }
        return imports;
    }
}
