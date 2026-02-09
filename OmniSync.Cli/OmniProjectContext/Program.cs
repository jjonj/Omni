using System;

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
        // To be implemented
    }

    private static void HandleContext()
    {
        // To be implemented
    }

    private static void HandleSync()
    {
        // To be implemented
    }
}
