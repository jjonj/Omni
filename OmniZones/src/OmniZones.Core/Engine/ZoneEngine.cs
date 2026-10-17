using OmniZones.Model;

namespace OmniZones.Engine;

public readonly record struct RectD(double X, double Y, double W, double H)
{
    public double Right => X + W;
    public double Bottom => Y + H;
    public double CenterX => X + W / 2;
    public double CenterY => Y + H / 2;
    public bool Contains(double px, double py) => px >= X && px < Right && py >= Y && py < Bottom;
}

public readonly record struct PlanWindow(string Id, string App, string? HomeZoneId);

/// <summary>
/// Pure zone math: no Win32, no UI, no state. Everything is in screen pixels of a monitor work area.
/// This is the portable heart of OmniZones.
/// </summary>
public static class ZoneEngine
{
    private const double Edge = 0.5;
    /// <summary>Accordion stacks shrink inactive windows to at most this fraction of the zone height.</summary>
    private const double AccordionStrip = 0.11;

    /// <summary>Effective kind: simple mode turns every zone into a plain zone.</summary>
    public static ZoneKind KindOf(Zone z, bool simple) => simple ? ZoneKind.Zone : z.Kind;

    /// <summary>Zones that take part in dragging at all. Simple mode has no peek docks.</summary>
    public static IEnumerable<Zone> ActiveZones(ZoneLayout layout, bool simple) =>
        simple ? layout.Zones.Where(z => z.Kind != ZoneKind.Peek) : layout.Zones;

    /// <summary>
    /// Effective rect per zone id. An occupied peek dock keeps its visible sliver on screen, so zones it overlaps shrink.
    /// </summary>
    public static Dictionary<string, RectD> ZoneRects(ZoneLayout layout, RectD area, bool simple, Func<Zone, bool>? peekOccupied = null)
    {
        var rects = new Dictionary<string, RectD>();
        foreach (var z in ActiveZones(layout, simple))
            rects[z.Id] = z.Kind == ZoneKind.Peek && !simple
                ? new RectD(area.X, area.Y + z.Y * area.H, z.W * area.W, z.H * area.H)
                : new RectD(area.X + z.X * area.W, area.Y + z.Y * area.H, z.W * area.W, z.H * area.H);

        if (simple || peekOccupied == null) return rects;
        foreach (var peek in layout.Zones.Where(z => z.Kind == ZoneKind.Peek && peekOccupied(z)))
        {
            var p = rects[peek.Id];
            foreach (var z in layout.Zones.Where(z => z.Kind != ZoneKind.Peek))
            {
                var r = rects[z.Id];
                if (r.Y < p.Bottom && r.Bottom > p.Y && r.X < p.Right)
                {
                    var cut = p.Right - r.X;
                    rects[z.Id] = new RectD(r.X + cut, r.Y, r.W - cut, r.H);
                }
            }
        }
        return rects;
    }

    /// <summary>Shrink a zone rect by the gap: half a gap between zones, a full gap at the monitor's edges.</summary>
    public static RectD Inset(RectD r, double gap, RectD area)
    {
        if (gap <= 0) return r;
        var l = r.X + (r.X <= area.X + Edge ? gap : gap / 2);
        var t = r.Y + (r.Y <= area.Y + Edge ? gap : gap / 2);
        var rr = r.Right - (r.Right >= area.Right - Edge ? gap : gap / 2);
        var b = r.Bottom - (r.Bottom >= area.Bottom - Edge ? gap : gap / 2);
        return new RectD(l, t, Math.Max(1, rr - l), Math.Max(1, b - t));
    }

    /// <summary>The rect a window actually gets in a zone: the zone rect minus the layout gap. Free zones are used exactly as drawn.</summary>
    public static RectD Inner(ZoneLayout layout, Zone zone, RectD rect, RectD area, bool simple = false) =>
        zone.Free && !simple ? rect : Inset(rect, layout.Gap, area);

    /// <summary>
    /// Where the <paramref name="index"/>-th of <paramref name="count"/> windows in <paramref name="zone"/> goes.
    /// Plain zones stack every member on the same rect. <paramref name="activeIndex"/> is the front window of an accordion.
    /// </summary>
    public static RectD WindowRect(ZoneLayout layout, Zone zone, RectD area, IReadOnlyDictionary<string, RectD> rects,
        int index, int count, int activeIndex, bool simple, bool peekOpen = false)
    {
        double g = layout.Gap;
        var kind = KindOf(zone, simple);
        var r = rects[zone.Id];
        if (kind == ZoneKind.Peek)
        {
            var winW = zone.PeekWindowWidth * area.W;
            return new RectD(peekOpen ? area.X + g : area.X - (winW - r.W), r.Y + g, winW, Math.Max(1, r.H - 2 * g));
        }

        var inner = Inner(layout, zone, r, area, simple);
        if (kind == ZoneKind.Zone || count <= 1) return inner;

        if (zone.Stack == StackStyle.Rows)
        {
            var h = (inner.H - (count - 1) * g) / count;
            return new RectD(inner.X, inner.Y + index * (h + g), inner.W, h);
        }

        var strip = Math.Min(inner.H * AccordionStrip, inner.H / count);
        var big = inner.H - (count - 1) * (strip + g);
        double y = inner.Y;
        for (var i = 0; i < count; i++)
        {
            var h = i == activeIndex ? big : strip;
            if (i == index) return new RectD(inner.X, y, inner.W, h);
            y += h + g;
        }
        return inner;
    }

    /// <summary>Zone under a point. Peek docks win over the zones they sit on, then free zones, then later zones win over earlier ones.</summary>
    public static Zone? ZoneAt(ZoneLayout layout, IReadOnlyDictionary<string, RectD> rects, double px, double py, bool simple)
    {
        var zones = ActiveZones(layout, simple).Where(z => rects.ContainsKey(z.Id)).ToList();
        var ordered = zones.Where(z => z.Kind == ZoneKind.Peek)
            .Concat(Enumerable.Reverse(zones.Where(z => z.Kind != ZoneKind.Peek && z.Free && !simple)))
            .Concat(Enumerable.Reverse(zones.Where(z => z.Kind != ZoneKind.Peek && !(z.Free && !simple))));
        return ordered.FirstOrDefault(z => rects[z.Id].Contains(px, py));
    }

    /// <summary>Nearest zone left or right of a point (Win+arrow). Vertical distance counts double.</summary>
    public static Zone? Neighbor(ZoneLayout layout, IReadOnlyDictionary<string, RectD> rects, double fromX, double fromY,
        bool right, string? excludeId, bool simple)
    {
        Zone? best = null;
        var bestScore = double.MaxValue;
        foreach (var z in ActiveZones(layout, simple))
        {
            if (z.Id == excludeId || !rects.TryGetValue(z.Id, out var r)) continue;
            double dx = r.CenterX - fromX, dy = r.CenterY - fromY;
            if (right ? dx < 1 : dx > -1) continue;
            var score = Math.Abs(dx) + 2 * Math.Abs(dy);
            if (score < bestScore) { bestScore = score; best = z; }
        }
        return best;
    }

    /// <summary>
    /// Assign windows to zones: remembered zone first, then the first free zone that prefers the app.
    /// Stacks take any number of windows, other zones one. Result maps window id to zone id (null = no home).
    /// </summary>
    public static Dictionary<string, string?> Plan(ZoneLayout layout, IEnumerable<PlanWindow> windows)
    {
        var result = new Dictionary<string, string?>();
        var taken = new HashSet<string>();
        bool Free(Zone z) => z.Kind == ZoneKind.Stack || !taken.Contains(z.Id);

        foreach (var w in windows.OrderByDescending(w => w.HomeZoneId != null))
        {
            var home = w.HomeZoneId == null ? null : layout.Zones.FirstOrDefault(z => z.Id == w.HomeZoneId);
            var target = home != null && Free(home) ? home : layout.Zones.FirstOrDefault(z => z.Apps.Contains(w.App) && Free(z));
            result[w.Id] = target?.Id;
            if (target != null) taken.Add(target.Id);
        }
        return result;
    }
}
