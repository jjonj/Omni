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
        var stateService = new StateService(projectRoot);
        var files = stateService.LoadState();
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

        if (!files.Any())
        {
            Console.WriteLine("\n[STRUCTURE]\n(No index found. Run 'opc sync' to generate context.)");
        }
        else
        {
            Console.WriteLine("\n[STRUCTURE]");
            int id = 1;
            var groupedFiles = files.GroupBy(f => Path.GetDirectoryName(f.Path))
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
                    var fileName = Path.GetFileName(file.Path);
                    var indent = string.IsNullOrEmpty(dir) ? "" : "  ";
                    Console.WriteLine($"{indent}{fileName}#{id++}");
                }
            }

            Console.WriteLine("\n[SKELETONS]");
            foreach (var file in files)
            {
                if (!string.IsNullOrWhiteSpace(file.Skeleton))
                {
                    Console.WriteLine($"\nFILE: {file.Path}");
                    Console.WriteLine(file.Skeleton.Trim());
                }
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

            var skeletonExtractor = new SkeletonExtractor();

    

            var files = engine.GenerateFileTree();

            var newList = new List<FileContext>();

    

            foreach (var relativePath in files)

            {

                var fullPath = Path.Combine(projectRoot, relativePath);

                var fileInfo = new FileInfo(fullPath);

                // Use milliseconds of the day as a compact hash

                var lastModifiedMs = (long)fileInfo.LastWriteTimeUtc.TimeOfDay.TotalMilliseconds;

                var ext = Path.GetExtension(relativePath).ToLower();

    

                var fileContext = new FileContext

                {

                    Path = relativePath,

                    LastModifiedMs = lastModifiedMs

                };

    

                if (ext == ".cs" || ext == ".py" || ext == ".kt")

                {

                    try

                    {

                        var content = File.ReadAllText(fullPath);

                        if (content.Length > 500)

                        {

                            fileContext.Skeleton = skeletonExtractor.Extract(content, ext);

                        }

                    }

                    catch { }

                }

    

                newList.Add(fileContext);

            }

    

            stateService.SaveState(newList);

            Console.WriteLine($"Sync complete. Indexed {newList.Count} files.");

        }

    }

    
