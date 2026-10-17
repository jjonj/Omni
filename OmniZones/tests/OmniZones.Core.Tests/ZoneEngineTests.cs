using OmniZones.Engine;
using OmniZones.Model;

namespace OmniZones.Core.Tests;

public class ZoneEngineTests
{
    private static readonly RectD Area = new(100, 50, 2000, 1000);

    private static ZoneLayout Layout(int gap, params Zone[] zones) => new() { Id = "t", Gap = gap, Zones = zones.ToList() };
    private static Zone Z(string id, double x, double y, double w, double h, ZoneKind k = ZoneKind.Zone, params string[] apps) =>
        new() { Id = id, Name = id, Kind = k, X = x, Y = y, W = w, H = h, Apps = apps.ToList() };

    [Fact]
    public void ZoneRects_AreFractionsOfTheWorkAreaOffsetByItsOrigin()
    {
        var r = ZoneEngine.ZoneRects(Layout(0, Z("a", 0.5, 0.25, 0.5, 0.5)), Area, simple: false);
        Assert.Equal(new RectD(1100, 300, 1000, 500), r["a"]);
    }

    [Fact]
    public void OccupiedPeek_ShrinksOverlappedZones_ButEmptyPeekDoesNot()
    {
        var layout = Layout(0, Z("peek", 0, 0, 0.1, 1, ZoneKind.Peek), Z("main", 0, 0, 0.5, 1));
        var occupied = ZoneEngine.ZoneRects(layout, Area, false, z => z.Kind == ZoneKind.Peek);
        var empty = ZoneEngine.ZoneRects(layout, Area, false, _ => false);
        Assert.Equal(300, occupied["main"].X);             // 100 + sliver of 200
        Assert.Equal(800, occupied["main"].W);
        Assert.Equal(100, empty["main"].X);
    }

    [Fact]
    public void SimpleMode_DropsPeekZones()
    {
        var layout = Layout(0, Z("peek", 0, 0, 0.1, 1, ZoneKind.Peek), Z("main", 0, 0, 0.5, 1));
        var r = ZoneEngine.ZoneRects(layout, Area, simple: true);
        Assert.False(r.ContainsKey("peek"));
        Assert.True(r.ContainsKey("main"));
    }

    [Fact]
    public void Inset_FullGapAtMonitorEdges_HalfGapBetweenZones()
    {
        var left = ZoneEngine.Inset(new RectD(100, 50, 1000, 1000), 12, Area);
        Assert.Equal(112, left.X);              // screen edge: full gap
        Assert.Equal(62, left.Y);
        Assert.Equal(1100 - 6, left.Right);     // interior edge: half gap
        Assert.Equal(1050 - 12, left.Bottom);
    }

    [Fact]
    public void WindowRect_PlainZone_StacksMembersOnTheSameRect()
    {
        var layout = Layout(10, Z("a", 0, 0, 1, 1));
        var rects = ZoneEngine.ZoneRects(layout, Area, false);
        var first = ZoneEngine.WindowRect(layout, layout.Zones[0], Area, rects, 0, 2, 1, false);
        var second = ZoneEngine.WindowRect(layout, layout.Zones[0], Area, rects, 1, 2, 1, false);
        Assert.Equal(first, second);
        Assert.Equal(new RectD(110, 60, 1980, 980), first);
    }

    [Fact]
    public void WindowRect_RowsStack_SplitsEvenly()
    {
        var zone = Z("s", 0, 0, 1, 1, ZoneKind.Stack); zone.Stack = StackStyle.Rows;
        var layout = Layout(0, zone);
        var rects = ZoneEngine.ZoneRects(layout, Area, false);
        var a = ZoneEngine.WindowRect(layout, zone, Area, rects, 0, 2, 0, false);
        var b = ZoneEngine.WindowRect(layout, zone, Area, rects, 1, 2, 0, false);
        Assert.Equal(500, a.H);
        Assert.Equal(a.Bottom, b.Y);
    }

    [Fact]
    public void WindowRect_Accordion_GivesTheActiveWindowTheMostHeight()
    {
        var zone = Z("s", 0, 0, 1, 1, ZoneKind.Stack); zone.Stack = StackStyle.Accordion;
        var layout = Layout(0, zone);
        var rects = ZoneEngine.ZoneRects(layout, Area, false);
        var heights = Enumerable.Range(0, 3).Select(i => ZoneEngine.WindowRect(layout, zone, Area, rects, i, 3, 1, false).H).ToList();
        Assert.True(heights[1] > heights[0] * 3);
        Assert.Equal(heights[0], heights[2], 3);
        Assert.Equal(1000, heights.Sum(), 3);
    }

    [Fact]
    public void WindowRect_SimpleMode_TreatsStackAsPlainZone()
    {
        var zone = Z("s", 0, 0, 1, 1, ZoneKind.Stack);
        var layout = Layout(0, zone);
        var rects = ZoneEngine.ZoneRects(layout, Area, true);
        var r = ZoneEngine.WindowRect(layout, zone, Area, rects, 0, 2, 0, simple: true);
        Assert.Equal(Area, r);
    }

    [Fact]
    public void WindowRect_Peek_HidesAllButTheSliverUntilOpened()
    {
        var peek = Z("p", 0, 0, 0.1, 1, ZoneKind.Peek); peek.PeekWindowWidth = 0.5;
        var layout = Layout(0, peek);
        var rects = ZoneEngine.ZoneRects(layout, Area, false);
        var closed = ZoneEngine.WindowRect(layout, peek, Area, rects, 0, 1, 0, false, peekOpen: false);
        var open = ZoneEngine.WindowRect(layout, peek, Area, rects, 0, 1, 0, false, peekOpen: true);
        Assert.Equal(1000, closed.W);
        Assert.Equal(Area.X + 200, closed.Right);   // exactly the visible sliver is on screen
        Assert.Equal(Area.X, open.X);
    }

    [Fact]
    public void ZoneAt_PrefersPeekThenLaterZones()
    {
        var layout = Layout(0, Z("back", 0, 0, 1, 1), Z("front", 0.25, 0.25, 0.5, 0.5), Z("peek", 0, 0, 0.1, 1, ZoneKind.Peek));
        var rects = ZoneEngine.ZoneRects(layout, Area, false);
        Assert.Equal("peek", ZoneEngine.ZoneAt(layout, rects, 150, 500, false)?.Id);
        Assert.Equal("front", ZoneEngine.ZoneAt(layout, rects, 1000, 500, false)?.Id);
        Assert.Equal("back", ZoneEngine.ZoneAt(layout, rects, 2000, 100, false)?.Id);
        Assert.Null(ZoneEngine.ZoneAt(layout, rects, 5000, 5000, false));
        Assert.Equal("back", ZoneEngine.ZoneAt(layout, rects, 150, 500, simple: true)?.Id);
    }

    [Fact]
    public void Neighbor_PicksTheNearestZoneInThatDirection_AndNullAtTheEdge()
    {
        var layout = Layout(0, Z("l", 0, 0, 0.33, 1), Z("m", 0.33, 0, 0.34, 1), Z("r", 0.67, 0, 0.33, 1));
        var rects = ZoneEngine.ZoneRects(layout, Area, false);
        var m = rects["m"];
        Assert.Equal("r", ZoneEngine.Neighbor(layout, rects, m.CenterX, m.CenterY, right: true, "m", false)?.Id);
        Assert.Equal("l", ZoneEngine.Neighbor(layout, rects, m.CenterX, m.CenterY, right: false, "m", false)?.Id);
        Assert.Null(ZoneEngine.Neighbor(layout, rects, rects["r"].CenterX, rects["r"].CenterY, right: true, "r", false));
    }

    [Fact]
    public void Neighbor_ZoneStraightBelow_IsNotRight()
    {
        var layout = Layout(0, Z("top", 0, 0, 0.5, 0.5), Z("bottom", 0, 0.5, 0.5, 0.5), Z("right", 0.5, 0, 0.5, 1));
        var rects = ZoneEngine.ZoneRects(layout, Area, false);
        var best = ZoneEngine.Neighbor(layout, rects, rects["top"].CenterX, rects["top"].CenterY, right: true, "top", false);
        Assert.Equal("right", best?.Id);
    }

    [Fact]
    public void Plan_UsesRememberedZoneFirst_ThenAppPreference_AndOneWindowPerPlainZone()
    {
        var term = Z("term", 0, 0, 0.3, 1, ZoneKind.Stack, "cmd");
        var layout = Layout(0, term, Z("web", 0.3, 0, 0.4, 1, ZoneKind.Zone, "firefox"), Z("notes", 0.7, 0, 0.3, 1, ZoneKind.Zone, "obsidian"));
        var plan = ZoneEngine.Plan(layout, new[]
        {
            new PlanWindow("ff1", "firefox", null),
            new PlanWindow("ff2", "firefox", null),
            new PlanWindow("c1", "cmd", null),
            new PlanWindow("c2", "cmd", null),
            new PlanWindow("odd", "calc", null),
            new PlanWindow("moved", "firefox", "notes"),
        });
        Assert.Equal("notes", plan["moved"]);   // remembered home wins, even against app preference
        Assert.Equal("web", plan["ff1"]);
        Assert.Null(plan["ff2"]);               // the web zone is taken
        Assert.Equal("term", plan["c1"]);
        Assert.Equal("term", plan["c2"]);       // stacks take any number
        Assert.Null(plan["odd"]);
    }
}
