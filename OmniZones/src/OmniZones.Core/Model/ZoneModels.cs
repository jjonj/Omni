using System.Text.Json;
using System.Text.Json.Serialization;

namespace OmniZones.Model;

public enum ZoneKind { Zone, Stack, Peek }
public enum StackStyle { Rows, Accordion }
public enum OccupiedAction { Swap, Stack }
public enum HoldKey { None, Ctrl, Alt }

/// <summary>
/// A snap target. All geometry is a fraction (0..1) of a monitor's work area so one layout fits any screen.
/// For <see cref="ZoneKind.Peek"/> zones X is 0, W is the visible sliver and <see cref="PeekWindowWidth"/> the window's full width.
/// </summary>
public sealed class Zone
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "Zone";
    public ZoneKind Kind { get; set; } = ZoneKind.Zone;
    public double X { get; set; }
    public double Y { get; set; }
    public double W { get; set; } = 0.5;
    public double H { get; set; } = 1;
    public StackStyle Stack { get; set; } = StackStyle.Rows;
    public double PeekWindowWidth { get; set; } = 0.32;
    /// <summary>Preferred apps (process names without .exe, lowercase). Used when a layout is applied.</summary>
    public List<string> Apps { get; set; } = new();
    /// <summary>
    /// Free zone: placed exactly where drawn. The editor does not snap it to the grid or other zones, it may overlap
    /// other zones or leave gaps, and the layout gap is not applied to it. Ignored in simple mode.
    /// </summary>
    public bool Free { get; set; }
}

/// <summary>Dropping a window of <see cref="Process"/> on <see cref="OnZoneId"/> switches to another layout and places it.</summary>
public sealed class SceneRule
{
    public string Process { get; set; } = "";
    public string OnZoneId { get; set; } = "";
    public string SwitchToLayoutId { get; set; } = "";
    public string PlaceInZoneId { get; set; } = "";
}

public sealed class ZoneLayout
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "Layout";
    /// <summary>Single character used in the Win+Alt+&lt;key&gt; hotkey that applies this layout.</summary>
    public string Key { get; set; } = "";
    /// <summary>Pixel gap between zones.</summary>
    public int Gap { get; set; }
    /// <summary>The zones of the main monitor.</summary>
    public List<Zone> Zones { get; set; } = new();
    /// <summary>Per-physical-display overrides use "id:" + monitor hardware identity. Legacy DISPLAYn keys remain readable.</summary>
    public Dictionary<string, List<Zone>> Monitors { get; set; } = new();
    public List<SceneRule> Scenes { get; set; } = new();

    /// <summary>A physical-display override follows its screen even when DisplayFusion changes its DISPLAYn number or primary status.</summary>
    public ZoneLayout ForMonitor(string device, bool primary, string identity = "")
    {
        List<Zone>? Find(string key) => Monitors.FirstOrDefault(
            pair => pair.Key.Equals(key, StringComparison.OrdinalIgnoreCase)).Value;
        var specific = identity.Length > 0 ? Find("id:" + identity) : null;
        var legacy = !primary && device.Length > 0 ? Find(device) : null;
        var chosen = specific is { Count: > 0 } ? specific :
            legacy is { Count: > 0 } ? legacy :
            primary ? Zones : DefaultLayouts.SecondaryZones;
        if (ReferenceEquals(chosen, Zones)) return this;
        return new ZoneLayout
        {
            Id = Id, Name = Name, Key = Key, Gap = Gap, Monitors = Monitors, Scenes = Scenes, Zones = chosen
        };
    }
}

public sealed class ZoneSettings
{
    /// <summary>Settings schema version, used by <see cref="ZoneSettingsStore"/> to migrate old files.</summary>
    public int Version { get; set; }
    public bool Enabled { get; set; } = true;
    /// <summary>Simple mode: plain zones only. No scenes, stacks, peek docks, app preferences or layout hotkeys.</summary>
    public bool SimpleMode { get; set; }
    /// <summary>true: zones only engage while Shift is held. false: they engage unless Shift is held.</summary>
    public bool RequireShift { get; set; }
    /// <summary>What dropping on an occupied zone does by default.</summary>
    public OccupiedAction OccupiedDefault { get; set; } = OccupiedAction.Swap;
    /// <summary>Holding this key while dropping does the opposite of <see cref="OccupiedDefault"/>.</summary>
    public HoldKey OccupiedHold { get; set; } = HoldKey.Ctrl;
    public bool OverrideWinArrows { get; set; } = true;
    public bool RestoreSizeOnUnsnap { get; set; } = true;
    public string ActiveLayoutId { get; set; } = "thirds";
    public List<ZoneLayout> Layouts { get; set; } = new();

    [JsonIgnore]
    public ZoneLayout ActiveLayout =>
        Layouts.FirstOrDefault(l => l.Id == ActiveLayoutId) ?? Layouts.FirstOrDefault() ?? DefaultLayouts.Create()[0];

    public ZoneSettings Clone() => ZoneJson.Deserialize<ZoneSettings>(ZoneJson.Serialize(this)) ?? new ZoneSettings();
}

public static class ZoneJson
{
    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        Converters = { new JsonStringEnumConverter() },
    };

    public static string Serialize<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T? Deserialize<T>(string json) => JsonSerializer.Deserialize<T>(json, Options);
}
