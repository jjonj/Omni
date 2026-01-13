using System;
using System.IO;
using System.Text.Json;
using System.Collections.Generic;
using System.Linq;

namespace FixSettings
{
    public class HotkeyConfig { public string Name { get; set; } = ""; public string Key { get; set; } = ""; public string Action { get; set; } = ""; }
    public class WindowLayout { public bool UseRatio { get; set; } = true; public double X { get; set; } public double Y { get; set; } public double Width { get; set; } public double Height { get; set; } public double RatioX { get; set; } public double RatioY { get; set; } public double RatioWidth { get; set; } public double RatioHeight { get; set; } }
    public class ProjectAction { public int Type { get; set; } public string Path { get; set; } = ""; public string Arguments { get; set; } = ""; public WindowLayout? Layout { get; set; } }
    public class Project { public Guid Id { get; set; } public string Name { get; set; } = ""; public string HotkeyName { get; set; } = ""; public List<ProjectAction> Actions { get; set; } = new(); }
    public class HubSettings { public Dictionary<string, string> ExeMappings { get; set; } = new(); public List<HotkeyConfig> Hotkeys { get; set; } = new(); public Dictionary<string, string> AiSessionNames { get; set; } = new(); public List<string> AutoApprovePatterns { get; set; } = new(); public List<string> AiPresets { get; set; } = new(); public List<Project> Projects { get; set; } = new(); }

    class Program
    {
        static void Main()
        {
            string path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync", "settings.json");
            if (!File.Exists(path)) return;

            string json = File.ReadAllText(path);
            var settings = JsonSerializer.Deserialize<HubSettings>(json);
            if (settings == null) return;

            // 1. Fix Rider mapping (remove literal quotes)
            if (settings.ExeMappings.ContainsKey("Rider"))
            {
                settings.ExeMappings["Rider"] = settings.ExeMappings["Rider"].Replace("\"", "");
            }

            // 2. Fix Wartribes project
            var wartribes = settings.Projects.FirstOrDefault(p => p.Name == "Wartribes");
            if (wartribes != null)
            {
                foreach (var action in wartribes.Actions)
                {
                    // If it was the broken Rider action
                    if (action.Type == 1 && action.Arguments == "Rider" && action.Path == "C:\\")
                    {
                        action.Path = "Rider";
                        action.Arguments = "";
                    }
                }
            }

            string newJson = JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(path, newJson);
            Console.WriteLine("Settings fixed successfully.");
        }
    }
}
