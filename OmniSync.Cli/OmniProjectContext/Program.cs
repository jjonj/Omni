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
        var stateService = new StateService(projectRoot);
        var files = stateService.LoadState();
        var gitHistoryService = new GitHistoryService(projectRoot);

        var sb = new StringBuilder();
        sb.AppendLine("<system-reminder>");
        sb.AppendLine("PROJECT CONTEXT:");
        sb.AppendLine($"Project: {projectName}");
        sb.AppendLine($"Root: {projectRoot}");

        var commits = gitHistoryService.GetRecentCommits(5);
        if (commits.Any())
        {
            sb.AppendLine("\n[NARRATIVE]");
            foreach (var commit in commits)
            {
                sb.AppendLine($"- {commit.Replace("\n", " ").Replace("\r", "")}");
            }
        }

        if (files.Any())
        {
            sb.AppendLine("\n[STRUCTURE]");
            int id = 1;
            var groupedFiles = files.GroupBy(f => Path.GetDirectoryName(f.Path))
                                    .OrderBy(g => g.Key);

            foreach (var group in groupedFiles)
            {
                var dir = group.Key;
                if (!string.IsNullOrEmpty(dir))
                {
                    sb.AppendLine($"{dir}\\");
                }

                foreach (var file in group)
                {
                    var fileName = Path.GetFileName(file.Path);
                    var indent = string.IsNullOrEmpty(dir) ? "" : "  ";
                    sb.AppendLine($"{indent}{fileName}#{id++}");
                }
            }
        }
        sb.AppendLine("</system-reminder>");

        var response = new
        {
            systemMessage = $"✦ OPC Synced: {projectName} ✦",
            hookSpecificOutput = new
            {
                hookEventName = "SessionStart",
                additionalContext = sb.ToString()
            }
        };

        Console.WriteLine(JsonSerializer.Serialize(response));
    }

            private static void HandleContext()

            {

                var projectRoot = Directory.GetCurrentDirectory();

                var stateService = new StateService(projectRoot);

                var files = stateService.LoadState();

                var gitHistoryService = new GitHistoryService(projectRoot);

        

                var sb = new StringBuilder();

                        sb.AppendLine("<system-reminder>");

                        sb.AppendLine("PROJECT CONTEXT:");

                        sb.AppendLine($"Project: {Path.GetFileName(projectRoot)}");

                        sb.AppendLine($"Root: {projectRoot}");

                

                        var commits = gitHistoryService.GetRecentCommits(5);

                

                if (commits.Any())

                {

                    sb.AppendLine("\n[NARRATIVE]");

                    foreach (var commit in commits)

                    {

                        sb.AppendLine($"- {commit.Replace("\n", " ").Replace("\r", "")}");

                    }

                }

        

                if (!files.Any())

                {

                    sb.AppendLine("\n[STRUCTURE]\n(No index found. Run 'opc sync' to generate context.)");

                }

                else

                {

                    sb.AppendLine("\n[STRUCTURE]");

                    int id = 1;

                    var groupedFiles = files.GroupBy(f => Path.GetDirectoryName(f.Path))

                                            .OrderBy(g => g.Key);

        

                    foreach (var group in groupedFiles)

                    {

                        var dir = group.Key;

                        if (!string.IsNullOrEmpty(dir))

                        {

                            sb.AppendLine($"{dir}\\");

                        }

        

                        foreach (var file in group)

                        {

                            var fileName = Path.GetFileName(file.Path);

                            var indent = string.IsNullOrEmpty(dir) ? "" : "  ";

                            sb.AppendLine($"{indent}{fileName}#{id++}");

                        }

                    }

                }

        

                sb.AppendLine("</system-reminder>");

        

                var response = new

                {

                    hookSpecificOutput = new

                    {

                        hookEventName = "BeforeAgent",

                        additionalContext = sb.ToString()

                    }

                };

        

                Console.WriteLine(JsonSerializer.Serialize(response));

            }

        

    

        private static void HandleSync()

        {

            var projectRoot = Directory.GetCurrentDirectory();

            var stateService = new StateService(projectRoot);

            stateService.Initialize();

    

            var fileSystemService = new FileSystemService();

            var engine = new ContextEngine(projectRoot, fileSystemService);

    

            var files = engine.GenerateFileTree();

            var newList = new List<FileContext>();

    

            foreach (var relativePath in files)

            {

                var fullPath = Path.Combine(projectRoot, relativePath);

                var fileInfo = new FileInfo(fullPath);

                // Use milliseconds of the day as a compact hash

                var lastModifiedMs = (long)fileInfo.LastWriteTimeUtc.TimeOfDay.TotalMilliseconds;

    

                newList.Add(new FileContext

                {

                    Path = relativePath,

                    LastModifiedMs = lastModifiedMs

                });

            }

    

            stateService.SaveState(newList);

            Console.WriteLine($"Sync complete. Indexed {newList.Count} files.");

        }

    }

    

    
