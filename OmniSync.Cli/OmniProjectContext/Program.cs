using System;
using System.IO;
using System.Linq;
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
        // Hook: SessionStart. Returns JSON for the CLI banner.
        var projectRoot = Directory.GetCurrentDirectory();
        var projectName = Path.GetFileName(projectRoot);
        
        Console.WriteLine("{");
        Console.WriteLine($"  \"projectName\": \"{projectName}\",");
        Console.WriteLine($"  \"projectRoot\": \"{projectRoot.Replace("\\", "\\\\")}\",");
        Console.WriteLine("  \"status\": \"Context ready\"");
        Console.WriteLine("}");
    }

    private static void HandleContext()
    {
        var projectRoot = Directory.GetCurrentDirectory();
        var fileSystemService = new FileSystemService();
        var engine = new ContextEngine(projectRoot, fileSystemService);
        var skeletonExtractor = new SkeletonExtractor();
        var gitHistoryService = new GitHistoryService(projectRoot);

        Console.WriteLine("--- OMNI PROJECT CONTEXT ---");
        
        // 1. Recent History
        var commits = gitHistoryService.GetRecentCommits(5);
        if (commits.Any())
        {
            Console.WriteLine("\n[RECENT NARRATIVE]");
            foreach (var commit in commits)
            {
                Console.WriteLine(commit);
            }
        }

        // 2. File Tree
        var files = engine.GenerateFileTree();
        Console.WriteLine("\n[FILE TREE]");
        foreach (var file in files)
        {
            Console.WriteLine(file);
        }

        // 3. Code Skeletons (for large or important files)
        Console.WriteLine("\n[CODE SKELETONS]");
        foreach (var file in files)
        {
            var ext = Path.GetExtension(file).ToLower();
            if (ext == ".cs" || ext == ".py")
            {
                var fullPath = Path.Combine(projectRoot, file);
                try
                {
                    var content = File.ReadAllText(fullPath);
                    if (content.Length > 1000) // Only extract skeleton for larger files
                    {
                        var skeleton = skeletonExtractor.Extract(content, ext);
                        Console.WriteLine($"\nFILE: {file}");
                        Console.WriteLine(skeleton);
                    }
                    else
                    {
                        // Small enough to just include
                        Console.WriteLine($"\nFILE: {file} (Full Content)");
                        Console.WriteLine(content);
                    }
                }
                catch (Exception)
                {
                    // Skip files we can't read
                }
            }
        }

        Console.WriteLine("\n--- END CONTEXT ---");
    }

    private static void HandleSync()
    {
        // Hook: SessionEnd. Incremental scan.
        // For now, just a placeholder.
        Console.WriteLine("Context synchronized.");
    }
}
