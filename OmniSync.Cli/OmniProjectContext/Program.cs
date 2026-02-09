using System;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Collections.Generic;
using System.Text;
using OmniProjectContext.Services;

namespace OmniProjectContext;

public class Program
{
    public static void Main(string[] args)
    {
        if (args.Length == 0)
        {
            PrintUsage();
            return;
        }

        string command = args[0].ToLower();

        switch (command)
        {
            case "session":
                HandleSession();
                break;
            case "context":
                HandleContext();
                break;
            case "sync":
                HandleSync();
                break;
            default:
                Console.WriteLine($"Unknown command: {command}");
                PrintUsage();
                break;
        }
    }

    private static void PrintUsage()
    {
        Console.WriteLine("OmniProjectContext (OPC) Usage:");
        Console.WriteLine("  opc session - Returns JSON for the CLI banner");
        Console.WriteLine("  opc context - Dumps codebase context for AI");
        Console.WriteLine("  opc sync    - Updates the local context index");
    }

    private static void HandleSession()
    {
        var projectRoot = Directory.GetCurrentDirectory();
        var projectName = Path.GetFileName(projectRoot);
        
        var response = new
        {
            systemMessage = $"✦ OPC Synced: {projectName} ✦"
        };
        
        Console.WriteLine(JsonSerializer.Serialize(response));
    }

    private static void HandleContext()
    {
        var projectRoot = Directory.GetCurrentDirectory();
        var fileSystemService = new FileSystemService();
        var engine = new ContextEngine(projectRoot, fileSystemService);
        var skeletonExtractor = new SkeletonExtractor();
        var gitHistoryService = new GitHistoryService(projectRoot);

        Console.WriteLine("<system-reminder>");
        Console.WriteLine("PROJECT CONTEXT:");
        Console.WriteLine($"Project: {Path.GetFileName(projectRoot)}");
        Console.WriteLine($"Root: {projectRoot}");

        var commits = gitHistoryService.GetRecentCommits(5);
        if (commits.Any())
        {
            Console.WriteLine("\n[NARRATIVE]");
            foreach (var commit in commits)
            {
                Console.WriteLine($"- {commit.Replace("\n", " ").Replace("\r", "")}");
            }
        }

        Console.WriteLine("\n[STRUCTURE]");
        var files = engine.GenerateFileTree();
        int id = 1;

        var groupedFiles = files.GroupBy(f => Path.GetDirectoryName(f))
                                .OrderBy(g => g.Key);

        foreach (var group in groupedFiles)
        {
            var dir = group.Key;
            if (!string.IsNullOrEmpty(dir))
            {
                Console.WriteLine($"{dir}\\");
            }

            foreach (var file in group)
            {
                var fileName = Path.GetFileName(file);
                var ext = Path.GetExtension(file).ToLower();
                var indent = string.IsNullOrEmpty(dir) ? "" : "  ";
                var outputLine = $"{indent}{fileName}#{id++}";

                if (ext == ".cs" || ext == ".py")
                {
                    try
                    {
                        var content = File.ReadAllText(Path.Combine(projectRoot, file));
                        var imports = skeletonExtractor.GetImports(content, ext);
                        if (imports.Any())
                        {
                            outputLine += "→" + string.Join("→", imports.Take(5));
                        }
                    }
                    catch { }
                }
                Console.WriteLine(outputLine);
            }
        }

        Console.WriteLine("\n[SKELETONS]");
        foreach (var file in files)
        {
            var ext = Path.GetExtension(file).ToLower();
            if (ext == ".cs" || ext == ".py" || ext == ".kt")
            {
                try
                {
                    var fullPath = Path.Combine(projectRoot, file);
                    var content = File.ReadAllText(fullPath);
                    if (content.Length > 1000)
                    {
                        var skeleton = skeletonExtractor.Extract(content, ext);
                        if (!string.IsNullOrWhiteSpace(skeleton))
                        {
                            Console.WriteLine($"\nFILE: {file}");
                            Console.WriteLine(skeleton.Trim());
                        }
                    }
                }
                catch { }
            }
        }

        Console.WriteLine("</system-reminder>");
    }

    private static void HandleSync()
    {
        var projectRoot = Directory.GetCurrentDirectory();
        var stateService = new StateService(projectRoot);
        stateService.Initialize();
        
        var fileSystemService = new FileSystemService();
        var engine = new ContextEngine(projectRoot, fileSystemService);
        var files = engine.GenerateFileTree();
        
        var syncData = new
        {
            lastSync = DateTime.Now,
            fileCount = files.Count
        };
        
        var statePath = Path.Combine(stateService.GetStatePath(), "sync_state.json");
        File.WriteAllText(statePath, JsonSerializer.Serialize(syncData));
    }
}
