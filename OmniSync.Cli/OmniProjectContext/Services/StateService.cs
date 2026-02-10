using System;
using System.IO;
using System.Collections.Generic;
using System.Text;

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

    public StateService(string rootPath)
    {
        _stateDir = System.IO.Path.Combine(rootPath, ".omni", "projectcontext");
        _stateFilePath = System.IO.Path.Combine(_stateDir, "sync_state.txt");
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

        using var writer = new StreamWriter(_stateFilePath, false, Encoding.UTF8);
        WriteNode(writer, root, "");
    }

    private void WriteNode(StreamWriter writer, Node node, string indent)
    {
        foreach (var file in node.Files.OrderBy(f => f.Path))
        {
            var fileName = System.IO.Path.GetFileName(file.Path);
            writer.WriteLine($"{file.LastModifiedMs}|{indent}↳{fileName}");
        }

        foreach (var dir in node.SubDirs.Values.OrderBy(d => d.Name))
        {
            long maxMs = GetMaxMs(dir);
            writer.WriteLine($"{maxMs}|{indent}↳\\{dir.Name}");
            WriteNode(writer, dir, indent + " ");
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

        var dirStack = new Stack<(int indent, string path)>();
        dirStack.Push((-1, ""));

        foreach (var line in File.ReadLines(_stateFilePath))
        {
            var parts = line.Split('|');
            if (parts.Length < 2) continue;

            long ms = long.TryParse(parts[0], out var m) ? m : 0;
            string content = parts[1];

            int indent = 0;
            while (indent < content.Length && content[indent] == ' ') indent++;
            string label = content.Substring(indent);

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
