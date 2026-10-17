namespace OmniZones.Model;

public static class DefaultLayouts
{
    // Authored on a 3440 x 1392 work area (21:9) and stored as fractions.
    private const double RefW = 3440, RefH = 1392;

    private static Zone Z(string id, string name, double x, double y, double w, double h, ZoneKind kind = ZoneKind.Zone,
        StackStyle stack = StackStyle.Rows, params string[] apps) => new()
    {
        Id = id, Name = name, Kind = kind, Stack = stack,
        X = Math.Round(x / RefW, 4), Y = Math.Round(y / RefH, 4), W = Math.Round(w / RefW, 4), H = Math.Round(h / RefH, 4),
        Apps = apps.ToList(),
    };

    private static Zone Peek(string id, string name, double visible, double winW, params string[] apps) => new()
    {
        Id = id, Name = name, Kind = ZoneKind.Peek, X = 0, Y = 0, W = Math.Round(visible / RefW, 4), H = 1,
        PeekWindowWidth = Math.Round(winW / RefW, 4), Apps = apps.ToList(),
    };

    private static readonly string[] Browsers = { "firefox", "chrome", "msedge", "brave", "vivaldi", "opera" };
    private static readonly string[] Terminals = { "windowsterminal", "cmd", "powershell", "pwsh", "conhost", "wezterm-gui", "alacritty" };

    private static readonly Lazy<List<Zone>> SecondaryShared = new(() => Create().First(l => l.Id == "halves").Zones);

    /// <summary>Zones of a secondary monitor that has none of its own. Shared instance: do not modify.</summary>
    public static List<Zone> SecondaryZones => SecondaryShared.Value;

    /// <summary>Fresh zones for a new profile's main monitor (the Writing arrangement).</summary>
    public static List<Zone> NewMainZones() => Create().First(l => l.Id == "writing").Zones;

    /// <summary>Fresh, editable zones for a secondary monitor (the Halves arrangement).</summary>
    public static List<Zone> NewSecondaryZones() => Create().First(l => l.Id == "halves").Zones;

    public static List<ZoneLayout> Create() => new()
    {
        new ZoneLayout { Id = "stage", Name = "Stage", Key = "1", Gap = 0,
            Zones = new()
            {
                Peek("peek", "Peek", 300, 1100, "discord"),
                Z("term", "Terminals", 0, 0, 760, 1392, ZoneKind.Stack, StackStyle.Rows, Terminals),
                Z("main", "Main stage", 760, 0, 1920, 1392, apps: Browsers),
                Z("notes", "Notes", 2680, 0, 760, 760, apps: "obsidian"),
                Z("files", "Files", 2680, 760, 760, 632, apps: "explorer"),
            },
            Scenes = new() { new SceneRule { Process = "obsidian", OnZoneId = "main", SwitchToLayoutId = "writing", PlaceInZoneId = "write" } } },
        new ZoneLayout { Id = "thirds", Name = "Thirds", Key = "2", Gap = 0,
            Zones = new()
            {
                Z("term", "Terminals", 0, 0, 1146, 1392, ZoneKind.Stack, StackStyle.Accordion, Terminals),
                Z("mid", "Middle", 1146, 0, 1148, 1392, apps: Browsers),
                Z("side", "Side stack", 2294, 0, 1146, 1392, ZoneKind.Stack, StackStyle.Rows, "obsidian", "explorer", "discord"),
            } },
        new ZoneLayout { Id = "writing", Name = "Writing", Key = "3", Gap = 0,
            Zones = new()
            {
                Peek("peek", "Peek", 300, 1100, "discord"),
                Z("files", "Vault", 0, 0, 760, 1392, apps: "explorer"),
                Z("write", "Writing", 760, 0, 1920, 1392, apps: "obsidian"),
                Z("ref", "Reference", 2680, 0, 760, 820, apps: Browsers),
                Z("term", "Terminals", 2680, 820, 760, 572, ZoneKind.Stack, StackStyle.Accordion, Terminals),
            } },
        new ZoneLayout { Id = "center", Name = "Center", Key = "4", Gap = 0,
            Zones = new() { Z("center", "Center", 860, 348, 1720, 696) } },
        new ZoneLayout { Id = "halves", Name = "Halves", Key = "5", Gap = 0,
            Zones = new()
            {
                Z("left", "Left", 0, 0, 1720, 1392),
                Z("right", "Right", 1720, 0, 1720, 1392),
            } },
    };
}
