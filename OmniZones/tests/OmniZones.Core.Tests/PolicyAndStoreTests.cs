using OmniZones.Engine;
using OmniZones.Model;

namespace OmniZones.Core.Tests;

public class SnapPolicyTests
{
    [Theory]
    [InlineData(false, false, true)]   // default: zones show while dragging
    [InlineData(false, true, false)]   // ...and Shift suspends them
    [InlineData(true, false, false)]   // require-shift: nothing without Shift
    [InlineData(true, true, true)]
    public void IsEngaged_FollowsTheRequireShiftSetting(bool requireShift, bool shiftHeld, bool expected)
    {
        var s = new ZoneSettings { RequireShift = requireShift };
        Assert.Equal(expected, SnapPolicy.IsEngaged(s, shiftHeld));
    }

    [Theory]
    [InlineData(0, 0, true)]
    [InlineData(-1080, -1080, true)]
    [InlineData(1, 0, false)]
    [InlineData(-1079, -1080, false)]
    public void IsAtTopEdge_OnlyMatchesThePhysicalMonitorEdge(double cursorY, double monitorTop, bool expected)
    {
        Assert.Equal(expected, SnapPolicy.IsAtTopEdge(cursorY, monitorTop));
    }

    [Theory]
    [InlineData(OccupiedAction.Swap, HoldKey.Ctrl, false, OccupiedAction.Swap)]
    [InlineData(OccupiedAction.Swap, HoldKey.Ctrl, true, OccupiedAction.Stack)]
    [InlineData(OccupiedAction.Stack, HoldKey.Alt, false, OccupiedAction.Stack)]
    [InlineData(OccupiedAction.Stack, HoldKey.Alt, true, OccupiedAction.Swap)]
    [InlineData(OccupiedAction.Swap, HoldKey.None, true, OccupiedAction.Swap)]   // hold key disabled
    public void ResolveOccupied_HoldKeyInvertsTheDefault(OccupiedAction def, HoldKey hold, bool held, OccupiedAction expected)
    {
        var s = new ZoneSettings { OccupiedDefault = def, OccupiedHold = hold };
        Assert.Equal(expected, SnapPolicy.ResolveOccupied(s, held));
    }

    private static (ZoneSettings, ZoneLayout) Defaults(bool simple)
    {
        var s = new ZoneSettings { Layouts = DefaultLayouts.Create(), SimpleMode = simple };
        return (s, s.Layouts.First(l => l.Id == "stage"));
    }

    [Fact]
    public void FindScene_TriggersOnTheConfiguredProcessAndZone()
    {
        var (s, stage) = Defaults(simple: false);
        var rule = SnapPolicy.FindScene(s, stage, "Obsidian", "main");
        Assert.Equal("writing", rule?.SwitchToLayoutId);
        Assert.Null(SnapPolicy.FindScene(s, stage, "obsidian", "notes"));
        Assert.Null(SnapPolicy.FindScene(s, stage, "firefox", "main"));
    }

    [Fact]
    public void FindScene_IsOffInSimpleMode()
    {
        var (s, stage) = Defaults(simple: true);
        Assert.Null(SnapPolicy.FindScene(s, stage, "obsidian", "main"));
    }

    [Fact]
    public void DescribeDrop_NamesWhatWillHappen()
    {
        var s = new ZoneSettings();
        var zone = new Zone { Id = "z", Kind = ZoneKind.Zone };
        Assert.Equal("Snap here", SnapPolicy.DescribeDrop(s, zone, 0, OccupiedAction.Swap, true, false));
        Assert.Equal("Swap places", SnapPolicy.DescribeDrop(s, zone, 1, OccupiedAction.Swap, true, false));
        Assert.Equal("Stack on top", SnapPolicy.DescribeDrop(s, zone, 1, OccupiedAction.Stack, true, false));
        Assert.Equal("Start scene", SnapPolicy.DescribeDrop(s, zone, 1, OccupiedAction.Swap, true, true));
        var stack = new Zone { Id = "s", Kind = ZoneKind.Stack };
        Assert.Equal("Join stack (3)", SnapPolicy.DescribeDrop(s, stack, 2, OccupiedAction.Swap, true, false));
        Assert.Equal("Snap here", SnapPolicy.DescribeDrop(new ZoneSettings { SimpleMode = true }, stack, 0, OccupiedAction.Swap, true, false));
    }
}

public class DefaultLayoutTests
{
    [Fact]
    public void DefaultLayouts_AreWellFormed()
    {
        var layouts = DefaultLayouts.Create();
        Assert.Equal(layouts.Count, layouts.Select(l => l.Id).Distinct().Count());
        Assert.Equal(layouts.Count, layouts.Select(l => l.Key).Distinct().Count());
        foreach (var l in layouts)
        {
            Assert.Equal(l.Zones.Count, l.Zones.Select(z => z.Id).Distinct().Count());
            Assert.True(l.Zones.Count(z => z.Kind == ZoneKind.Peek) <= 1);
            foreach (var z in l.Zones)
            {
                Assert.InRange(z.X, 0, 1); Assert.InRange(z.Y, 0, 1);
                Assert.InRange(z.X + z.W, 0, 1.0001); Assert.InRange(z.Y + z.H, 0, 1.0001);
            }
            foreach (var r in l.Scenes)
            {
                Assert.Contains(l.Zones, z => z.Id == r.OnZoneId);
                var target = layouts.Single(t => t.Id == r.SwitchToLayoutId);
                Assert.Contains(target.Zones, z => z.Id == r.PlaceInZoneId);
            }
        }
    }
}

public class SettingsStoreTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "omnizones-tests-" + Guid.NewGuid().ToString("N"));
    private string FilePath => Path.Combine(_dir, "settings.json");
    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    [Fact]
    public void NewStore_SeedsDefaults()
    {
        var store = new ZoneSettingsStore(FilePath);
        Assert.NotEmpty(store.Current.Layouts);
        Assert.False(store.Current.SimpleMode);
        Assert.False(store.Current.RequireShift);
        Assert.Equal(OccupiedAction.Swap, store.Current.OccupiedDefault);
    }

    [Fact]
    public void Save_RoundTripsThroughDisk_AndRaisesChanged()
    {
        var store = new ZoneSettingsStore(FilePath);
        var raised = 0;
        store.Changed += () => raised++;
        store.Update(s => { s.SimpleMode = true; s.RequireShift = true; s.OccupiedDefault = OccupiedAction.Stack; s.OccupiedHold = HoldKey.Alt; });
        Assert.Equal(1, raised);

        var reloaded = new ZoneSettingsStore(FilePath).Current;
        Assert.True(reloaded.SimpleMode);
        Assert.True(reloaded.RequireShift);
        Assert.Equal(OccupiedAction.Stack, reloaded.OccupiedDefault);
        Assert.Equal(HoldKey.Alt, reloaded.OccupiedHold);
        Assert.Equal(DefaultLayouts.Create().Count, reloaded.Layouts.Count);
        Assert.Contains("\"Stack\"", File.ReadAllText(FilePath));   // enums are readable strings
    }

    [Fact]
    public void CorruptFile_IsSetAsideAndDefaultsReturn()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(FilePath, "{ not json");
        var store = new ZoneSettingsStore(FilePath);
        Assert.NotEmpty(store.Current.Layouts);
        Assert.True(File.Exists(FilePath + ".broken"));
    }

    [Fact]
    public void UnknownActiveLayout_FallsBackToTheFirst()
    {
        var store = new ZoneSettingsStore(FilePath);
        store.Update(s => s.ActiveLayoutId = "does-not-exist");
        Assert.Equal(store.Current.Layouts[0].Id, store.Current.ActiveLayoutId);
    }

    [Fact]
    public void Clone_IsIndependent()
    {
        var a = new ZoneSettings { Layouts = DefaultLayouts.Create() };
        var b = a.Clone();
        b.Layouts[0].Zones[0].Name = "changed";
        Assert.NotEqual("changed", a.Layouts[0].Zones[0].Name);
    }
}

public class SimpleModeAndMonitorTests
{
    [Fact]
    public void SimpleMode_AlwaysStacksAndIgnoresTheHoldKey()
    {
        var s = new ZoneSettings { SimpleMode = true, OccupiedDefault = OccupiedAction.Swap, OccupiedHold = HoldKey.Ctrl };
        Assert.Equal(OccupiedAction.Stack, SnapPolicy.ResolveOccupied(s, false));
        Assert.Equal(OccupiedAction.Stack, SnapPolicy.ResolveOccupied(s, true));
    }

    [Fact]
    public void ForMonitor_UsesOwnZonesOrFallsBackToShared()
    {
        var l = new ZoneLayout { Id = "a", Zones = { new Zone { Id = "shared" } } };
        l.Monitors["DISPLAY2"] = new List<Zone> { new Zone { Id = "own" } };
        Assert.Equal("own", l.ForMonitor("DISPLAY2", primary: false).Zones.Single().Id);
        // main monitor: the profile's own zones, whatever the Monitors table says
        Assert.Equal("shared", l.ForMonitor("DISPLAY1", primary: true).Zones.Single().Id);
        // a secondary monitor without zones of its own gets the Halves arrangement
        Assert.Equal(new[] { "left", "right" }, l.ForMonitor("DISPLAY3", primary: false).Zones.Select(z => z.Id).ToArray());
    }

    [Fact]
    public void FreeZones_WinOverlapsAndSkipTheGap()
    {
        var area = new RectD(0, 0, 1000, 1000);
        var big = new Zone { Id = "big", W = 1, H = 1 };
        var free = new Zone { Id = "free", X = 0.4, Y = 0.4, W = 0.2, H = 0.2, Free = true };
        var layout = new ZoneLayout { Id = "l", Gap = 20, Zones = { big, free } };
        var rects = ZoneEngine.ZoneRects(layout, area, false);
        Assert.Equal("free", ZoneEngine.ZoneAt(layout, rects, 500, 500, false)?.Id);
        Assert.Equal(rects["free"], ZoneEngine.Inner(layout, free, rects["free"], area));
        Assert.NotEqual(rects["big"], ZoneEngine.Inner(layout, big, rects["big"], area));
    }

    [Fact]
    public void OldDefaultGapsMigrateToZero()
    {
        var path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "omnizones-migrate-" + Guid.NewGuid() + ".json");
        try
        {
            var old = new ZoneSettings { Layouts = { new ZoneLayout { Id = "a", Gap = 12 }, new ZoneLayout { Id = "b", Gap = 5 } } };
            System.IO.File.WriteAllText(path, ZoneJson.Serialize(old));
            var store = new ZoneSettingsStore(path);
            Assert.Equal(0, store.Current.Layouts[0].Gap);
            Assert.Equal(5, store.Current.Layouts[1].Gap);
        }
        finally { System.IO.File.Delete(path); }
    }
}
