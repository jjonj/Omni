using OmniZones.Engine;
using OmniZones.Model;
using OmniZones.Native;

namespace OmniZones.Runtime;

/// <summary>A window that OmniZones has put into a zone.</summary>
internal sealed class Placement
{
    public IntPtr Hwnd;
    public string LayoutId = "";
    public string ZoneId = "";
    public RectD Area;
    public long Order;
    /// <summary>Size/position to give back when the window leaves its zone.</summary>
    public RectD? RestoreFrame;
    /// <summary>Where we last put it; a window that moved away from this is no longer considered zoned.</summary>
    public RectD? Assigned;

    public Placement Clone() => (Placement)MemberwiseClone();
}

internal readonly record struct DragOrigin(Placement? Placement, RectD? Frame);
internal sealed record Snapshot(IntPtr Hwnd, RectD? Frame, Placement? Placement);
public sealed record SavedZoneAssignment(string LayoutId, string ZoneId);

/// <summary>
/// Runtime brain: tracks which windows sit in which zones, watches for window drags, and executes drops and hotkey actions.
/// Everything runs on one UI thread (the runtime's dispatcher), so there is no locking.
/// </summary>
public sealed class ZoneManager
{
    private const double DriftPx = 12;

    private readonly ZoneSettingsStore _store;
    private readonly Dictionary<IntPtr, Placement> _placed = new();
    private readonly Dictionary<IntPtr, Dictionary<string, string>> _homes = new();
    private readonly Dictionary<IntPtr, Placement> _preMax = new();
    private readonly Dictionary<(RectD, string), IntPtr> _active = new();
    private readonly Dictionary<RectD, OverlayWindow> _overlays = new();
    private readonly List<List<Snapshot>> _history = new();
    private long _order;
    private IntPtr _peekOpen;
    private DateTime _peekOutSince = DateTime.MaxValue;
    private bool _suppressStoreEvent;
    private string _lastLayoutId = "";

    // drag state
    private IntPtr _dragHwnd;
    private DragOrigin _origin;
    private bool _snappable, _engaged, _hold;
    private Zone? _hot;
    private RectD _hotArea;
    private (int W, int H)? _lastSize;
    private int _sizeChanges;
    // Shift also triggers Windows 11's own snap-on-release. When Shift means "free move" we put the window back where it was dropped.
    private RectD? _freeFrame;
    private (IntPtr Hwnd, RectD Frame, DateTime Until)? _pendingFree;
    // Where the window under the cursor sat before the drag began. By the first tick that sees the drag it has already moved.
    private (IntPtr Hwnd, RectD Frame)? _hover;
    // Crossing to a monitor with another DPI rescales the window; that must not look like a resize drag.
    private string? _dragDevice;
    private bool _crossedMonitors;
    private bool _plainOnStack;
    private bool _maximizeOnDrop;
    private List<MonitorInfo> _mons = new();
    private DateTime _monsAt = DateTime.MinValue;

    /// <summary>Human-readable activity messages (the Hub forwards them to its activity log).</summary>
    public event Action<string>? Activity;

    public ZoneManager(ZoneSettingsStore store)
    {
        _store = store;
        _lastLayoutId = store.Current.ActiveLayoutId;
        store.Changed += OnStoreChanged;
    }

    private ZoneSettings S => _store.Current;

    public SavedZoneAssignment? CaptureAssignment(IntPtr hwnd)
    {
        Prune();
        return _placed.TryGetValue(hwnd, out var placement) ? new(placement.LayoutId, placement.ZoneId) : null;
    }

    /// <summary>Restore one setup window without moving unrelated windows or switching the active layout.</summary>
    public bool RestoreAssignment(IntPtr hwnd, string layoutId, string zoneId, MonitorInfo monitor)
    {
        if (S.ActiveLayoutId != layoutId || !WindowTools.Exists(hwnd)) return false;
        var layout = S.ActiveLayout.ForMonitor(monitor.Device, monitor.Primary, monitor.Identity);
        var zone = layout.Zones.FirstOrDefault(z => z.Id == zoneId);
        if (zone == null || zone.Kind == ZoneKind.Peek) return false;
        _placed[hwnd] = new Placement { Hwnd = hwnd, LayoutId = layoutId, ZoneId = zoneId, Area = monitor.Work,
            Order = ++_order, RestoreFrame = WindowTools.RestoreFrame(hwnd) };
        Home(hwnd, layoutId, zoneId);
        RelayoutArea(monitor.Work);
        return true;
    }

    private List<MonitorInfo> Mons()
    {
        if (_mons.Count == 0 || (DateTime.UtcNow - _monsAt).TotalMilliseconds > 1000) { _mons = WindowTools.Monitors(); _monsAt = DateTime.UtcNow; }
        return _mons;
    }

    private MonitorInfo MonitorAtCached(double x, double y) =>
        Mons().FirstOrDefault(m => m.Full.Contains(x, y)) ?? WindowTools.MonitorAt(x, y);

    /// <summary>The active layout as it applies on one monitor (that monitor's own zones, or the shared ones).</summary>
    private ZoneLayout LayoutAt(RectD area) =>
        Mons().FirstOrDefault(m => m.Work == area) is { } m ? S.ActiveLayout.ForMonitor(m.Device, m.Primary, m.Identity) : S.ActiveLayout;
    private bool Simple => S.SimpleMode;
    private void Log(string text) => Activity?.Invoke(text);

    /// <summary>Settings changed (editor / settings window): re-place everything under the new geometry.</summary>
    private void OnStoreChanged()
    {
        if (_suppressStoreEvent) return;
        void Apply()
        {
            if (S.ActiveLayoutId != _lastLayoutId) ApplyLayout(S.ActiveLayoutId, announce: false);
            else RelayoutAll();
            if (!S.Enabled) HideOverlays();
        }
        // The runtime marshals store events onto its own dispatcher; calling directly is fine when already there.
        if (_dispatcher != null && !_dispatcher.CheckAccess()) _dispatcher.BeginInvoke(Apply); else Apply();
    }

    private System.Windows.Threading.Dispatcher? _dispatcher;
    internal void BindDispatcher(System.Windows.Threading.Dispatcher d) => _dispatcher = d;

    // ------------------------------------------------------------------ placement bookkeeping

    private void Untrack(IntPtr hwnd) { _placed.Remove(hwnd); if (_peekOpen == hwnd) _peekOpen = IntPtr.Zero; }

    private void Prune()
    {
        var layout = S.ActiveLayout;
        foreach (var p in _placed.Values.ToList())
        {
            var zone = LayoutAt(p.Area).Zones.FirstOrDefault(z => z.Id == p.ZoneId);
            if (zone != null && Simple && zone.Kind == ZoneKind.Peek && WindowTools.Exists(p.Hwnd)) { Float(p); continue; }
            var gone = !WindowTools.Exists(p.Hwnd) || p.LayoutId != layout.Id || zone == null;
            if (!gone && p.Assigned is { } a && ZoneEngine.KindOf(zone!, Simple) != ZoneKind.Peek)
            {
                var f = WindowTools.Frame(p.Hwnd);
                gone = f == null || Math.Abs(f.Value.X - a.X) > DriftPx || Math.Abs(f.Value.Y - a.Y) > DriftPx;
            }
            if (gone) Untrack(p.Hwnd);
        }
    }

    private List<Placement> Members(RectD area, string zoneId)
    {
        Prune();
        return _placed.Values.Where(p => p.Area == area && p.ZoneId == zoneId).OrderBy(p => p.Order).ToList();
    }

    private Dictionary<string, RectD> Rects(RectD area) =>
        ZoneEngine.ZoneRects(LayoutAt(area), area, Simple, z => z.Kind == ZoneKind.Peek && Members(area, z.Id).Count > 0);

    private void Home(IntPtr hwnd, string layoutId, string zoneId)
    {
        if (!_homes.TryGetValue(hwnd, out var d)) _homes[hwnd] = d = new();
        d[layoutId] = zoneId;
    }

    private RectD AreaOf(RectD frame) => MonitorAtCached(frame.CenterX, frame.CenterY).Work;

    /// <summary>Move every tracked window to where its zone says it should be.</summary>
    private void RelayoutAll()
    {
        Prune();
        foreach (var area in _placed.Values.Select(p => p.Area).Distinct().ToList()) RelayoutArea(area);
    }

    private void RelayoutArea(RectD area)
    {
        var layout = LayoutAt(area);
        var rects = Rects(area);
        foreach (var zone in ZoneEngine.ActiveZones(layout, Simple))
        {
            var members = Members(area, zone.Id);
            if (members.Count == 0) continue;
            _active.TryGetValue((area, zone.Id), out var activeHwnd);
            var activeIndex = members.FindIndex(m => m.Hwnd == activeHwnd);
            if (activeIndex < 0) activeIndex = members.Count - 1;
            for (var i = 0; i < members.Count; i++)
            {
                var m = members[i];
                var rect = ZoneEngine.WindowRect(layout, zone, area, rects, i, members.Count, activeIndex, Simple, _peekOpen == m.Hwnd);
                m.Assigned = rect;
                m.LayoutId = layout.Id;
                if (!WindowTools.IsAt(m.Hwnd, rect)) WindowTools.Place(m.Hwnd, rect);
                if (ZoneEngine.KindOf(zone, Simple) == ZoneKind.Peek && _peekOpen == m.Hwnd) WindowTools.Raise(m.Hwnd);
            }
        }
        // Zones that no longer exist release their windows.
        foreach (var orphan in _placed.Values.Where(p => p.Area == area && layout.Zones.All(z => z.Id != p.ZoneId)).ToList())
            Float(orphan);
    }

    /// <summary>Release a window from its zone and give it back its own size.</summary>
    private void Float(Placement p, int cascade = 0)
    {
        Untrack(p.Hwnd);
        if (!WindowTools.Exists(p.Hwnd)) return;
        var rf = p.RestoreFrame ?? WindowTools.Frame(p.Hwnd);
        if (rf is not { } r) return;
        var a = p.Area;
        var x = Math.Clamp(a.X + 90 + cascade * 60, a.X, Math.Max(a.X, a.Right - r.W));
        var y = Math.Clamp(a.Y + 90 + cascade * 60, a.Y, Math.Max(a.Y, a.Bottom - r.H));
        WindowTools.Place(p.Hwnd, new RectD(x, y, r.W, r.H));
    }

    // ------------------------------------------------------------------ undo

    private void PushHistory(IEnumerable<IntPtr>? extra = null)
    {
        var hwnds = _placed.Keys.Concat(extra ?? Enumerable.Empty<IntPtr>()).Distinct().Where(WindowTools.Exists);
        _history.Add(hwnds.Select(h => new Snapshot(h, WindowTools.Frame(h), _placed.TryGetValue(h, out var p) ? p.Clone() : null)).ToList());
        if (_history.Count > 30) _history.RemoveAt(0);
    }

    public void Undo()
    {
        if (_history.Count == 0) { Log("Nothing to undo"); return; }
        var snap = _history[^1];
        _history.RemoveAt(_history.Count - 1);
        foreach (var s in snap)
        {
            if (!WindowTools.Exists(s.Hwnd)) continue;
            if (s.Placement != null) _placed[s.Hwnd] = s.Placement; else Untrack(s.Hwnd);
            if (s.Frame is { } f) WindowTools.Place(s.Hwnd, f);
        }
        Log("Undid the last arrangement");
    }

    // ------------------------------------------------------------------ drops

    private static string Key(IntPtr h) => h.ToInt64().ToString();

    /// <summary>Put <paramref name="hwnd"/> into <paramref name="zone"/> on <paramref name="area"/>.</summary>
    private void Drop(IntPtr hwnd, Zone zone, RectD area, DragOrigin origin, bool holdKey, bool snapshot = true, bool plainOnStack = false)
    {
        var s = S;
        var layout = s.ActiveLayout;
        if (snapshot) PushHistory(new[] { hwnd });

        var process = WindowTools.ProcessName(hwnd);
        var scene = SnapPolicy.FindScene(s, layout, process, zone.Id);
        if (scene != null)
        {
            ApplyLayout(scene.SwitchToLayoutId, announce: false, snapshot: false);
            layout = LayoutAt(area);
            var target = layout.Zones.FirstOrDefault(z => z.Id == scene.PlaceInZoneId);
            if (target == null) { Log($"Scene \"{layout.Name}\" started"); return; }
            zone = target;
            origin = new DragOrigin(null, origin.Frame);
            Log($"{process} dropped on {scene.OnZoneId}: scene \"{layout.Name}\"");
        }

        var kind = ZoneEngine.KindOf(zone, Simple);
        var onStackAsZone = plainOnStack && kind == ZoneKind.Stack;
        if (kind == ZoneKind.Zone || onStackAsZone)
        {
            var occupants = Members(area, zone.Id).Where(m => m.Hwnd != hwnd).ToList();
            var rejoin = origin.Placement is { } same && same.Area == area && same.ZoneId == zone.Id;
            if (occupants.Count > 0 && !rejoin && SnapPolicy.ResolveOccupied(s, holdKey) == OccupiedAction.Swap)
            {
                // In a stack the window under the cursor is the one that gets swapped out.
                var top = occupants[^1];
                if (onStackAsZone)
                {
                    var (cx, cy) = WindowTools.CursorPos();
                    top = occupants.LastOrDefault(m => WindowTools.Frame(m.Hwnd) is { } mf && mf.Contains(cx, cy)) ?? top;
                }
                var op = origin.Placement;
                if (op != null && !(op.Area == area && op.ZoneId == zone.Id) && LayoutAt(op.Area).Zones.Any(z => z.Id == op.ZoneId))
                {
                    top.ZoneId = op.ZoneId; top.Area = op.Area; top.Order = ++_order;
                    Home(top.Hwnd, layout.Id, op.ZoneId);
                    RelayoutArea(op.Area);
                }
                else if (origin.Frame is { } f)
                {
                    Untrack(top.Hwnd);
                    WindowTools.Place(top.Hwnd, f);
                }
                else Float(top);
                Log($"Swapped with {WindowTools.ProcessName(top.Hwnd)}");
            }
            else if (occupants.Count > 0 && !rejoin) Log("Stacked on top of the zone's window");
        }

        var restore = origin.Placement?.RestoreFrame ?? origin.Frame ?? WindowTools.RestoreFrame(hwnd);
        _placed[hwnd] = new Placement { Hwnd = hwnd, LayoutId = layout.Id, ZoneId = zone.Id, Area = area, Order = ++_order, RestoreFrame = restore };
        Home(hwnd, layout.Id, zone.Id);
        _active[(area, zone.Id)] = hwnd;
        _preMax.Remove(hwnd);
        RelayoutArea(area);
        WindowTools.Activate(hwnd);
        if (kind == ZoneKind.Peek) Log($"{process} docked to the edge. Hover the edge to peek.");
        else if (scene == null) Log($"{process} snapped to {zone.Name}");
    }

    // ------------------------------------------------------------------ layouts

    /// <summary>Switch layouts on every monitor and send windows to their remembered or preferred zones.</summary>
    public void ApplyLayout(string layoutId, bool announce = true, bool snapshot = true)
    {
        var s = S;
        var layout = s.Layouts.FirstOrDefault(l => l.Id == layoutId);
        if (layout == null) return;
        var windows = WindowTools.EnumerateAppWindows();
        if (snapshot) PushHistory(windows);

        _suppressStoreEvent = true;
        try { _store.Update(c => c.ActiveLayoutId = layoutId); }
        finally { _suppressStoreEvent = false; }
        _lastLayoutId = layoutId;
        _peekOpen = IntPtr.Zero;
        _active.Clear();

        var monitors = WindowTools.Monitors();
        if (!Simple)
        {
            foreach (var mon in monitors)
            {
                var area = mon.Work;
                var onArea = windows.Where(h => WindowTools.Frame(h) is { } f && AreaOf(f) == area).ToList();
                var plan = ZoneEngine.Plan(layout.ForMonitor(mon.Device, mon.Primary, mon.Identity), onArea.Select(h => new PlanWindow(Key(h),
                    WindowTools.ProcessName(h), _homes.TryGetValue(h, out var d) && d.TryGetValue(layout.Id, out var z) ? z : null)));
                var cascade = 0;
                foreach (var h in onArea)
                {
                    plan.TryGetValue(Key(h), out var target);
                    _placed.TryGetValue(h, out var existing);
                    if (target != null)
                    {
                        var rf = existing?.RestoreFrame ?? WindowTools.RestoreFrame(h);
                        _placed[h] = new Placement { Hwnd = h, LayoutId = layout.Id, ZoneId = target, Area = area, Order = ++_order, RestoreFrame = rf };
                        Home(h, layout.Id, target);
                    }
                    else if (existing != null) Float(existing, cascade++);
                }
            }
        }
        foreach (var p in _placed.Values.ToList()) p.LayoutId = layout.Id;
        RelayoutAll();
        if (announce) Log($"Layout {layout.Name}");
    }

    public void ApplyLayoutByKey(char key)
    {
        var layout = S.Layouts.FirstOrDefault(l => l.Key.Length == 1 && char.ToLowerInvariant(l.Key[0]) == char.ToLowerInvariant(key));
        if (layout != null) ApplyLayout(layout.Id);
    }

    /// <summary>Win+Alt+T: pull every window whose app belongs to the layout's stack into that stack.</summary>
    public void Gather()
    {
        var (cx, cy) = WindowTools.CursorPos();
        var area = MonitorAtCached(cx, cy).Work;
        var layout = LayoutAt(area);
        var stack = layout.Zones.FirstOrDefault(z => z.Kind == ZoneKind.Stack && z.Apps.Count > 0) ?? layout.Zones.FirstOrDefault(z => z.Kind == ZoneKind.Stack);
        if (stack == null) { Log($"{layout.Name} has no stack zone"); return; }
        var windows = WindowTools.EnumerateAppWindows().Where(h => stack.Apps.Contains(WindowTools.ProcessName(h))).ToList();
        if (windows.Count == 0) { Log("No matching windows to gather"); return; }
        PushHistory(windows);
        foreach (var h in windows)
        {
            _placed.TryGetValue(h, out var existing);
            _placed[h] = new Placement { Hwnd = h, LayoutId = layout.Id, ZoneId = stack.Id, Area = area, Order = ++_order,
                RestoreFrame = existing?.RestoreFrame ?? WindowTools.RestoreFrame(h) };
            Home(h, layout.Id, stack.Id);
        }
        RelayoutAll();
        Log($"Gathered {windows.Count} window(s) into {stack.Name}");
    }

    // ------------------------------------------------------------------ hotkey actions

    private IntPtr ActionWindow()
    {
        var h = WindowTools.Foreground();
        return h != IntPtr.Zero && WindowTools.IsAppWindow(h) ? h : IntPtr.Zero;
    }

    /// <summary>True when the foreground window is something OmniZones can move.</summary>
    public bool CanAct => ActionWindow() != IntPtr.Zero;

    /// <summary>True for windows OmniZones placed (or that were maximized): Win+Down means "restore" for these.</summary>
    public bool IsManaged(IntPtr hwnd) => _placed.ContainsKey(hwnd) || _preMax.ContainsKey(hwnd) || WindowTools.IsMaximized(hwnd);

    public void WinArrow(int vk)
    {
        var hwnd = ActionWindow();
        if (hwnd == IntPtr.Zero) return;
        var frame = WindowTools.Frame(hwnd);
        if (frame is not { } f) return;
        _placed.TryGetValue(hwnd, out var p);

        if (vk == Win32.VK_UP)
        {
            PushHistory(new[] { hwnd });
            if (p != null) { _preMax[hwnd] = p.Clone(); Untrack(hwnd); }
            WindowTools.Maximize(hwnd);
            RelayoutAll();
            Log("Filled the screen");
            return;
        }
        if (vk == Win32.VK_DOWN)
        {
            PushHistory(new[] { hwnd });
            if (WindowTools.IsMaximized(hwnd))
            {
                if (_preMax.Remove(hwnd, out var pm) && LayoutAt(pm.Area).Zones.FirstOrDefault(z => z.Id == pm.ZoneId) is { } z0)
                {
                    WindowTools.Restore(hwnd);
                    Drop(hwnd, z0, pm.Area, new DragOrigin(null, pm.RestoreFrame), false, snapshot: false);
                }
                else WindowTools.Restore(hwnd);
            }
            else if (p != null) { Float(p); Log("Unsnapped"); RelayoutAll(); }
            return;
        }

        var right = vk == Win32.VK_RIGHT;
        var area = p?.Area ?? AreaOf(f);
        var rects = Rects(area);
        // An untracked window (e.g. resized by a DPI change after a hop) steps from the zone under its center, not from its
        // raw center, otherwise the nearest zone "to the left" can be the one it already fills and it re-snaps in place.
        var curId = p?.ZoneId ?? ZoneEngine.ZoneAt(LayoutAt(area), rects, f.CenterX, f.CenterY, Simple)?.Id;
        var (fx, fy) = curId != null && rects.TryGetValue(curId, out var zr) ? (zr.CenterX, zr.CenterY) : (f.CenterX, f.CenterY);
        var best = ZoneEngine.Neighbor(LayoutAt(area), rects, fx, fy, right, curId, Simple);
        var target = area;
        if (best == null)
        {
            // Hop to the next monitor in that direction, entering at its near edge.
            var next = ZoneEngine.NextArea(WindowTools.Monitors().Select(m => m.Work), area, f.Y, f.Bottom, right);
            if (next is not { } n) { Log(right ? "Already at the right edge" : "Already at the left edge"); return; }
            target = n;
            var nr = Rects(n);
            var ex = right ? n.X + 1 : n.Right - 1;
            best = ZoneEngine.ZoneAt(LayoutAt(n), nr, ex, Math.Clamp(fy - area.Y + n.Y, n.Y, n.Bottom - 1), Simple)
                   ?? ZoneEngine.ActiveZones(LayoutAt(n), Simple).FirstOrDefault();
            if (best == null) return;
        }
        PushHistory(new[] { hwnd });
        var restore = p?.RestoreFrame ?? WindowTools.RestoreFrame(hwnd);
        var origin = new DragOrigin(p?.Clone(), restore);
        Untrack(hwnd);
        if (origin.Placement == null) origin = new DragOrigin(null, f);
        Drop(hwnd, best, target, origin, false, snapshot: false);
    }

    public void CycleStack(int delta)
    {
        var hwnd = ActionWindow();
        if (hwnd == IntPtr.Zero || !_placed.TryGetValue(hwnd, out var p)) { Log("The focused window is not in a stack"); return; }
        var zone = LayoutAt(p.Area).Zones.FirstOrDefault(z => z.Id == p.ZoneId);
        if (zone == null || ZoneEngine.KindOf(zone, Simple) != ZoneKind.Stack) { Log("The focused window is not in a stack"); return; }
        var members = Members(p.Area, zone.Id);
        var next = members[(members.FindIndex(m => m.Hwnd == hwnd) + delta + members.Count) % members.Count];
        _active[(p.Area, zone.Id)] = next.Hwnd;
        RelayoutArea(p.Area);
        WindowTools.Activate(next.Hwnd);
    }

    public void TogglePeek()
    {
        if (Simple) return;
        var peek = _placed.Values.FirstOrDefault(p => LayoutAt(p.Area).Zones.Any(z => z.Id == p.ZoneId && z.Kind == ZoneKind.Peek));
        if (peek == null) { Log("Nothing is docked in a peek zone"); return; }
        SetPeek(_peekOpen == peek.Hwnd ? IntPtr.Zero : peek.Hwnd);
        if (_peekOpen != IntPtr.Zero) WindowTools.Activate(_peekOpen);
    }

    private void SetPeek(IntPtr hwnd)
    {
        if (_peekOpen == hwnd) return;
        _peekOpen = hwnd;
        RelayoutAll();
    }

    // ------------------------------------------------------------------ the tick: drag + hover

    /// <summary>Polled by the runtime every ~15 ms.</summary>
    public void Tick()
    {
        if (!S.Enabled)
        {
            if (_dragHwnd != IntPtr.Zero) EndDrag(commit: false);
            return;
        }
        ServicePendingFree();
        var hwnd = WindowTools.DraggedWindow();
        if (hwnd != IntPtr.Zero && hwnd != _dragHwnd)
        {
            if (_dragHwnd != IntPtr.Zero) EndDrag(commit: true);
            BeginDrag(hwnd);
        }
        else if (hwnd == IntPtr.Zero && _dragHwnd != IntPtr.Zero)
        {
            EndDrag(commit: true);
            return;
        }
        if (_dragHwnd != IntPtr.Zero) UpdateDrag();
        else { TrackHover(); HoverPeek(); }
    }

    private void TrackHover()
    {
        var (cx, cy) = WindowTools.CursorPos();
        var h = WindowTools.RootWindowAt(cx, cy);
        _hover = h != IntPtr.Zero && WindowTools.RestoreFrame(h) is { } f ? (h, f) : null;
    }

    private void ServicePendingFree()
    {
        if (_pendingFree is not { } p) return;
        if (DateTime.UtcNow > p.Until || !WindowTools.Exists(p.Hwnd)) { _pendingFree = null; return; }
        if (WindowTools.DraggedWindow() != IntPtr.Zero) { _pendingFree = null; return; }
        if (WindowTools.IsAt(p.Hwnd, p.Frame)) return;
        WindowTools.Place(p.Hwnd, p.Frame);
        _pendingFree = null;
    }

    private void BeginDrag(IntPtr hwnd)
    {
        _dragHwnd = hwnd;
        _hot = null; _engaged = false; _hold = false;
        _lastSize = null; _sizeChanges = 0; _freeFrame = null; _plainOnStack = false; _maximizeOnDrop = false;
        var (cx, cy) = WindowTools.CursorPos();
        _dragDevice = MonitorAtCached(cx, cy).Device;
        _crossedMonitors = false;
        _snappable = WindowTools.IsResizable(hwnd) && !WindowTools.IsResizeHit(hwnd, cx, cy) && WindowTools.IsAppWindow(hwnd);
        if (!_snappable) { _origin = default; return; }

        _placed.TryGetValue(hwnd, out var existing);
        var before = _hover is { } hv && hv.Hwnd == hwnd ? hv.Frame : WindowTools.RestoreFrame(hwnd);
        _origin = new DragOrigin(existing?.Clone(), before);
        PushHistory(new[] { hwnd });
        if (existing != null)
        {
            Untrack(hwnd);
            if (S.RestoreSizeOnUnsnap && existing.RestoreFrame is { } rf)
            {
                WindowTools.ResizeFrame(hwnd, rf.W, rf.H);
                _lastSize = null;
            }
            RelayoutArea(existing.Area);
        }
    }

    private void UpdateDrag()
    {
        if (!_snappable) return;
        if (DetectResize()) { _snappable = false; _engaged = false; _hot = null; _maximizeOnDrop = false; HideOverlays(); return; }

        var s = S;
        var shift = WindowTools.IsDown(Win32.VK_SHIFT);
        _engaged = SnapPolicy.IsEngaged(s, shift);
        _hold = s.OccupiedHold switch
        {
            HoldKey.Ctrl => WindowTools.IsDown(Win32.VK_CONTROL),
            HoldKey.Alt => WindowTools.IsDown(Win32.VK_MENU),
            _ => false,
        };
        if (!_engaged)
        {
            _hot = null;
            _maximizeOnDrop = false;
            HideOverlays();
            _freeFrame = shift && !s.RequireShift ? WindowTools.Frame(_dragHwnd) : null;
            return;
        }
        _freeFrame = null;

        var (cx, cy) = WindowTools.CursorPos();
        var mon = MonitorAtCached(cx, cy);
        var area = mon.Work;
        if (SnapPolicy.IsAtTopEdge(cy, mon.Full.Y))
        {
            _maximizeOnDrop = true;
            _hot = null;
            HideOverlays();
            return;
        }
        _maximizeOnDrop = false;
        var layout = LayoutAt(area);
        var rects = Rects(area);
        _hot = ZoneEngine.ZoneAt(layout, rects, cx, cy, Simple);
        _hotArea = area;
        // Alt over a stack: drop on it like on a plain zone instead of joining. (With Alt as the hold key it is consumed here.)
        var alt = !Simple && WindowTools.IsDown(Win32.VK_MENU);
        _plainOnStack = alt && _hot != null && _hot.Kind == ZoneKind.Stack;
        if (_plainOnStack && s.OccupiedHold == HoldKey.Alt) _hold = false;

        var action = SnapPolicy.ResolveOccupied(s, _hold);
        var zones = new List<OverlayZone>();
        foreach (var z in ZoneEngine.ActiveZones(layout, Simple))
        {
            if (!rects.TryGetValue(z.Id, out var r)) continue;
            var inner = ZoneEngine.Inner(layout, z, r, area, Simple);
            var kind = ZoneEngine.KindOf(z, Simple);
            var members = Members(area, z.Id).Count(m => m.Hwnd != _dragHwnd);
            var isScene = SnapPolicy.FindScene(s, layout, WindowTools.ProcessName(_dragHwnd), z.Id) != null;
            var hot = z == _hot;
            zones.Add(new OverlayZone(z.Name, ZoneDetail(z, kind, r, members), inner, hot,
                hot ? SnapPolicy.DescribeDrop(s, z, members, action, _origin.Frame != null, isScene, _plainOnStack) : null, isScene, kind == ZoneKind.Peek));
        }
        ShowOverlay(area, zones);
    }

    private static string ZoneDetail(Zone z, ZoneKind kind, RectD r, int members) => kind switch
    {
        ZoneKind.Stack => $"stack · {z.Stack.ToString().ToLowerInvariant()} · {members} in",
        ZoneKind.Peek => "peek dock",
        _ => members > 0 ? $"{(int)r.W} × {(int)r.H} · {members} in" : $"{(int)r.W} × {(int)r.H}",
    };

    /// <summary>
    /// The hit test at drag start can miss (we poll, so the cursor may already have left the border). Backstop: a move keeps
    /// the size constant except for one change when a maximized window restores as you drag - 2+ changes = resize.
    /// </summary>
    private bool DetectResize()
    {
        if (WindowTools.IsMaximized(_dragHwnd)) return false;
        var (cx, cy) = WindowTools.CursorPos();
        var device = MonitorAtCached(cx, cy).Device;
        // A window carried to another monitor gets rescaled when the DPI differs. That is a move, never a border resize
        // (real border resizes are already excluded by the hit test when the drag starts), so stop judging by size.
        if (device != _dragDevice) { _dragDevice = device; _crossedMonitors = true; }
        var size = WindowTools.OuterSize(_dragHwnd);
        if (_crossedMonitors) { _lastSize = size; _sizeChanges = 0; return false; }
        if (_lastSize is { } last && last != size) _sizeChanges++;
        _lastSize = size;
        return _sizeChanges >= 2;
    }

    private void EndDrag(bool commit)
    {
        var hwnd = _dragHwnd;
        var hot = _hot;
        var exists = WindowTools.Exists(hwnd);
        var maximize = commit && _snappable && _engaged && _maximizeOnDrop && exists;
        var shouldDrop = commit && _snappable && _engaged && hot != null && exists;
        var area = _hotArea; var origin = _origin; var hold = _hold; var plain = _plainOnStack;
        var free = _freeFrame;
        _dragHwnd = IntPtr.Zero; _hot = null; _engaged = false; _snappable = false; _freeFrame = null; _maximizeOnDrop = false;
        HideOverlays();
        if (maximize)
        {
            if (origin.Placement is { } p) _preMax[hwnd] = p.Clone();
            WindowTools.Maximize(hwnd);
            RelayoutAll();
            Log("Filled the screen");
            return;
        }
        if (commit && free is { } ff && exists) _pendingFree = (hwnd, ff, DateTime.UtcNow.AddMilliseconds(500));
        if (shouldDrop) Drop(hwnd, hot!, area, origin, hold, snapshot: false, plainOnStack: plain);
    }

    private void ShowOverlay(RectD area, IReadOnlyList<OverlayZone> zones)
    {
        foreach (var (a, o) in _overlays) if (a != area) o.HideOverlay();
        if (!_overlays.TryGetValue(area, out var overlay)) _overlays[area] = overlay = new OverlayWindow();
        overlay.Present(area, zones);
    }

    private void HideOverlays() { foreach (var o in _overlays.Values) o.HideOverlay(); }

    /// <summary>Reveal a docked window while the cursor rests on its sliver; tuck it away shortly after the cursor leaves.</summary>
    private void HoverPeek()
    {
        if (Simple || _placed.Count == 0) return;
        var peek = _placed.Values.FirstOrDefault(p => LayoutAt(p.Area).Zones.Any(z => z.Id == p.ZoneId && z.Kind == ZoneKind.Peek));
        if (peek == null) return;
        var (cx, cy) = WindowTools.CursorPos();
        if (_peekOpen == IntPtr.Zero)
        {
            var rects = Rects(peek.Area);
            if (rects.TryGetValue(peek.ZoneId, out var sliver) && cx >= sliver.X - 2 && cx < sliver.Right && cy >= sliver.Y && cy < sliver.Bottom)
            { SetPeek(peek.Hwnd); _peekOutSince = DateTime.MaxValue; }
            return;
        }
        var frame = WindowTools.Frame(_peekOpen);
        var inside = frame is { } f && f.Contains(cx, cy);
        if (inside) _peekOutSince = DateTime.MaxValue;
        else if (_peekOutSince == DateTime.MaxValue) _peekOutSince = DateTime.UtcNow;
        else if ((DateTime.UtcNow - _peekOutSince).TotalMilliseconds > 450) { SetPeek(IntPtr.Zero); _peekOutSince = DateTime.MaxValue; }
    }

    /// <summary>Create the overlay windows up front: the first WPF window is slow to appear, and a drag should show zones instantly.</summary>
    public void Prewarm()
    {
        foreach (var m in WindowTools.Monitors())
            if (!_overlays.ContainsKey(m.Work)) _overlays[m.Work] = new OverlayWindow();
    }

    public void Shutdown()
    {
        _store.Changed -= OnStoreChanged;
        foreach (var o in _overlays.Values) o.Close();
        _overlays.Clear();
    }
}
