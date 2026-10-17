using System.Collections.Generic;
using System.Linq;
using System.Windows.Forms;
using OmniZones.Model;

namespace OmniSync.Hub.Logic;

public sealed record SetupOption(string Key, string Label);
public static class SetupOptions
{
    public static List<SetupOption> Monitors() => Screen.AllScreens.Select((s, i) => new SetupOption(s.DeviceName,
        $"Monitor {i + 1}{(s.Primary ? " (primary)" : "")} — {s.WorkingArea.Width} × {s.WorkingArea.Height}")).ToList();
    public static List<SetupOption> Zones()
    {
        var result = new List<SetupOption> { new("", "Saved position and size (no zone)") };
        var store = new ZoneSettingsStore();
        foreach (var layout in store.Current.Layouts)
            foreach (var zone in layout.Zones.Concat(layout.Monitors.Values.SelectMany(z => z)).Concat(DefaultLayouts.SecondaryZones)
                .Where(z => z.Kind != ZoneKind.Peek).DistinctBy(z => z.Id))
                result.Add(new(layout.Id + "|" + zone.Id, layout.Name + " / " + zone.Name));
        return result;
    }
}
