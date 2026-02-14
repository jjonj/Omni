using System;
using System.IO;
using System.Collections.Generic;
using System.Text;
using System.Linq;
using System.Text.Json;

namespace OmniProjectContext.Services;

public class FileContext
{
    public string Path { get; set; } = "";
    public long LastModifiedMs { get; set; }
}

public class StateService
{
    private readonly string _stateDir;
    private readonly string _stateFilePath;
    private readonly string _projectName;

    public StateService(string rootPath)
    {
        _stateDir = System.IO.Path.Combine(rootPath, ".omni", "projectcontext");
        _stateFilePath = System.IO.Path.Combine(_stateDir, "sync_state.txt");
        _projectName = System.IO.Path.GetFileName(rootPath);
    }

    public void Initialize()
    {
        if (!Directory.Exists(_stateDir))
        {
            Directory.CreateDirectory(_stateDir);
        }
    }

    private class Node {
        public string Name = "";
        public List<FileContext> Files = new();
        public Dictionary<string, Node> SubDirs = new();
    }

    public void SaveState(List<FileContext> files)
    {
        var root = new Node();
        foreach (var f in files)
        {
            var parts = f.Path.Split(System.IO.Path.DirectorySeparatorChar);
            var current = root;
            for (int i = 0; i < parts.Length - 1; i++)
            {
                if (!current.SubDirs.ContainsKey(parts[i]))
                    current.SubDirs[parts[i]] = new Node { Name = parts[i] };
                current = current.SubDirs[parts[i]];
            }
            current.Files.Add(f);
        }

        var sb = new StringBuilder();
        WriteNode(sb, root, "");

        var wrapper = new
        {
            version = "2.0",
            project_name = _projectName,
            last_sync_ms = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            data = sb.ToString()
        };

        var json = JsonSerializer.Serialize(wrapper, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(_stateFilePath, json, Encoding.UTF8);
    }

    private void WriteNode(StringBuilder sb, Node node, string indent)
    {
        foreach (var file in node.Files.OrderBy(f => f.Path))
        {
            var fileName = System.IO.Path.GetFileName(file.Path);
            sb.AppendLine($"{file.LastModifiedMs}|{indent}↳{fileName}");
        }

        foreach (var dir in node.SubDirs.Values.OrderBy(d => d.Name))
        {
            long maxMs = GetMaxMs(dir);
            sb.AppendLine($"{maxMs}|{indent}↳\\{dir.Name}");
            WriteNode(sb, dir, indent + " ");
        }
    }

    private long GetMaxMs(Node node)
    {
        long max = node.Files.Any() ? node.Files.Max(f => f.LastModifiedMs) : 0;
        foreach (var sub in node.SubDirs.Values)
        {
            max = Math.Max(max, GetMaxMs(sub));
        }
        return max;
    }

    public List<FileContext> LoadState()
    {
        var files = new List<FileContext>();
        if (!File.Exists(_stateFilePath)) return files;

        string content = File.ReadAllText(_stateFilePath);
        string textData;

        if (content.Trim().StartsWith("{"))
        {
            try
            {
                using var doc = JsonDocument.Parse(content);
                textData = doc.RootElement.GetProperty("data").GetString() ?? "";
            }
            catch
            {
                textData = "";
            }
        }
        else
        {
            textData = content;
        }

        var dirStack = new Stack<(int indent, string path)>();
        dirStack.Push((-1, ""));

        using var reader = new StringReader(textData);
        string? line;
        while ((line = reader.ReadLine()) != null)
        {
            var parts = line.Split('|');
            if (parts.Length < 2) continue;

            long ms = long.TryParse(parts[0], out var m) ? m : 0;
            string labelContent = parts[1];

            int indent = 0;
            while (indent < labelContent.Length && labelContent[indent] == ' ') indent++;
            string label = labelContent.Substring(indent);

            if (label.StartsWith("↳\\"))
            {
                string dirName = label.Substring(2);
                while (dirStack.Count > 1 && dirStack.Peek().indent >= indent) dirStack.Pop();
                string parentPath = dirStack.Peek().path;
                string fullPath = string.IsNullOrEmpty(parentPath) ? dirName : System.IO.Path.Combine(parentPath, dirName);
                dirStack.Push((indent, fullPath));
            }
            else if (label.StartsWith("↳"))
            {
                string fileName = label.Substring(1);
                while (dirStack.Count > 1 && dirStack.Peek().indent >= indent) dirStack.Pop();
                string dirPath = dirStack.Peek().path;
                files.Add(new FileContext
                {
                    Path = System.IO.Path.Combine(dirPath, fileName),
                    LastModifiedMs = ms
                });
            }
        }
        return files;
    }

    public string GetStatePath() => _stateDir;
}
