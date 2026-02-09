using System;
using System.Text;
using System.Text.RegularExpressions;

namespace OmniProjectContext.Services;

public class SkeletonExtractor
{
    public string Extract(string content, string extension)
    {
        if (string.IsNullOrWhiteSpace(content)) return string.Empty;

        return extension.ToLower() switch
        {
            ".cs" => ExtractCSharp(content),
            ".py" => ExtractPython(content),
            _ => content
        };
    }

    private string ExtractCSharp(string content)
    {
        var sb = new StringBuilder();
        var lines = content.Split('\n');

        var signatureRegex = new Regex(@"^\s*(public|private|protected|internal|static|class|interface|namespace|enum|struct|void|string|int|bool|Task|async).*", RegexOptions.Compiled);

        foreach (var line in lines)
        {
            var trimmedLine = line.Trim();
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

    private string ExtractPython(string content)
    {
        var sb = new StringBuilder();
        var lines = content.Split('\n');

        foreach (var line in lines)
        {
            var trimmed = line.Trim();
            if ((trimmed.StartsWith("class ") || trimmed.StartsWith("def ")) && trimmed.EndsWith(":"))
            {
                sb.AppendLine(line.TrimEnd());
            }
            else if (trimmed.StartsWith("import ") || trimmed.StartsWith("from "))
            {
                sb.AppendLine(line.TrimEnd());
            }
        }
        return sb.ToString();
    }
}
