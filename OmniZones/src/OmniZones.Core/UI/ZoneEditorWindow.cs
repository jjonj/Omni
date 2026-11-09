using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using OmniZones.Engine;
using OmniZones.Model;
using OmniZones.Native;
using OmniZones.Runtime;

namespace OmniZones.UI;

/// <summary>
/// Layout and zone editor. Drag zones on the monitor preview to move them, drag the handles to resize.
/// Edits happen on a working copy and are applied with Save.
/// </summary>
internal sealed class ZoneEditorWindow : Window
{
    private const double Grid = 20, SnapDist = 28, MinSize = 160;

    private readonly ZoneRuntime _rt;
    private RectD _ref;
    private string _monitor = "";   // device name of the monitor being edited
    private readonly List<MonitorInfo> _mons = WindowTools.Monitors();
    private readonly Canvas _canvas = new();
    private readonly Viewbox _view;
    private readonly StackPanel _side = new() { Margin = new Thickness(16, 12, 16, 16) };
    private readonly TextBlock _status = Ui.Text("", 12, muted: true);
    private readonly TextBlock _caption = Ui.Text("", 12, muted: true);

    private List<ZoneLayout> _layouts;   // working copies; only the profile in _layoutId is editable and saved
    private readonly string _layoutId;
    private string? _selId;
    private bool _dirty;
    private readonly Button _undoButton;
    private readonly Stack<(string layouts, string? selection, string monitor)> _undo = new();
    private string _lastLayoutState = "", _savedLayoutState = "", _lastMonitor = "";
    private string? _lastSelection;
    private bool _dragHistoryRecorded;

    // pointer drag
    private string? _dragHandle;
    private Zone? _dragZone;
    private Point _dragStart;
    private RectD _dragOrig;

    private double RW => _ref.W;
    private double RH => _ref.H;
    private bool Simple => _rt.Store.Current.SimpleMode;
    private ZoneLayout L => _layouts.First(l => l.Id == _layoutId);
    /// <summary>The profile this editor was opened for.</summary>
    public string LayoutId => _layoutId;
    private MonitorInfo? CurMon => _mons.FirstOrDefault(m => m.Device == _monitor);
    private bool IsMain => CurMon?.Primary ?? true;
    private string ProfileKey => CurMon?.Identity is { Length: > 0 } identity ? "id:" + identity : _monitor;
    private string? ExistingKey => L.Monitors.Keys.FirstOrDefault(key =>
        key.Equals(ProfileKey, StringComparison.OrdinalIgnoreCase));

    /// <summary>Use a stable physical monitor profile when present; keep main-monitor defaults and legacy keys readable.</summary>
    private List<Zone> Zs
    {
        get
        {
            if (ExistingKey is { } key && L.Monitors[key].Count > 0) return L.Monitors[key];
            if (IsMain) return L.Zones;
            if (!L.Monitors.TryGetValue(ProfileKey, out var own) || own.Count == 0)
                L.Monitors[ProfileKey] = own = L.Id.Equals("full", StringComparison.OrdinalIgnoreCase)
                    ? L.Zones.Select(z => ZoneJson.Deserialize<Zone>(ZoneJson.Serialize(z))!).ToList()
                    : DefaultLayouts.NewSecondaryZones();
            return own;
        }
    }
    private Zone? Sel => Zs.FirstOrDefault(z => z.Id == _selId);
    private double ViewScale => _view.ActualWidth > 10 ? _view.ActualWidth / RW : 0.3;

    public ZoneEditorWindow(ZoneRuntime rt)
    {
        _rt = rt;
        var main = _mons.FirstOrDefault(m => m.Primary);
        _ref = main?.Work ?? new RectD(0, 0, 1920, 1032);
        _monitor = main?.Device ?? "";
        _layouts = rt.Store.Current.Layouts.Select(Clone).ToList();
        _layoutId = rt.Store.Current.ActiveLayoutId;
        if (_layouts.All(l => l.Id != _layoutId)) _layoutId = _layouts[0].Id;
        _selId = Zs.FirstOrDefault()?.Id;

        Title = $"OmniZones editor · {L.Name}";
        Width = 1360; Height = 800; MinWidth = 900; MinHeight = 560;
        Ui.Theme(this);
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        FontFamily = new FontFamily("Segoe UI");

        _canvas.Width = RW; _canvas.Height = RH;
        _canvas.Background = Ui.Brush("#11141B");
        _canvas.MouseLeftButtonDown += OnDown;
        _canvas.MouseMove += OnMove;
        _canvas.MouseLeftButtonUp += OnUp;
        _canvas.LostMouseCapture += (_, _) => { _dragZone = null; _dragHistoryRecorded = false; };
        _view = new Viewbox { Stretch = Stretch.Uniform, Child = _canvas };

        var stage = new StackPanel { Margin = new Thickness(16) };
        stage.Children.Add(Ui.Text("Select and drag zones to move or resize them. Split, add, or remove zones in the panel. Ctrl+Z undoes edits.", 12, muted: true));
        stage.Children.Add(new Border { Height = 8 });
        var frame = new Border { Background = Ui.Brush("#1E2129"), Padding = new Thickness(8), CornerRadius = new CornerRadius(10), Child = _view };
        stage.Children.Add(frame);
        stage.Children.Add(_caption);

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(16, 8, 16, 12) };
        buttons.Children.Add(Ui.Button("Save and apply", () => { Save(); Close(); }, primary: true));
        _undoButton = Ui.Button("Undo (Ctrl+Z)", Undo);
        buttons.Children.Add(_undoButton);
        buttons.Children.Add(Ui.Button("Revert", Revert));
        buttons.Children.Add(_status);
        _status.VerticalAlignment = VerticalAlignment.Center;

        var sideScroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = _side };
        var sideDock = new DockPanel { Background = Ui.Card, Width = 400 };
        DockPanel.SetDock(buttons, Dock.Bottom);
        sideDock.Children.Add(buttons);
        sideDock.Children.Add(sideScroll);

        var root = new DockPanel();
        DockPanel.SetDock(sideDock, Dock.Right);
        root.Children.Add(sideDock);
        root.Children.Add(new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = stage });
        Content = root;

        Closing += OnClosing;
        KeyDown += (_, e) =>
        {
            if (e.Key != Key.Z || (Keyboard.Modifiers & ModifierKeys.Control) == 0 ||
                Keyboard.FocusedElement is TextBox) return;
            Undo();
            e.Handled = true;
        };
        SizeChanged += (_, _) => DrawCanvas();
        BuildSide();
        DrawCanvas();
        UpdateCaption();
        _lastLayoutState = _savedLayoutState = ZoneJson.Serialize(_layouts);
        _lastSelection = _selId;
        _lastMonitor = _monitor;
        _undoButton.IsEnabled = false;
    }

    private void UpdateCaption() => _caption.Text = $"{L.Name} · {MonitorName(CurMon)} · preview of a {(int)RW} × {(int)RH} work area. Zones are saved as fractions.";

    private string MonitorName(MonitorInfo? m)
    {
        if (m == null) return "Monitor";
        var digits = new string(m.Device.Where(char.IsDigit).ToArray());
        var n = digits.Length > 0 ? digits : (_mons.IndexOf(m) + 1).ToString();
        return $"Display {n}{(m.Primary ? " (main)" : "")} · {(int)m.Full.W}×{(int)m.Full.H}";
    }

    private void SelectMonitor(string device)
    {
        _monitor = device;
        if (CurMon is { } m) _ref = m.Work;
        _canvas.Width = RW; _canvas.Height = RH;
        _selId = Zs.FirstOrDefault()?.Id;
        UpdateCaption(); BuildSide(); DrawCanvas();
    }

    private static ZoneLayout Clone(ZoneLayout l) => ZoneJson.Deserialize<ZoneLayout>(ZoneJson.Serialize(l))!;

    // ------------------------------------------------------------------ geometry helpers

    private RectD Px(Zone z) => z.Kind == ZoneKind.Peek
        ? new RectD(0, z.Y * RH, z.W * RW, z.H * RH)
        : new RectD(z.X * RW, z.Y * RH, z.W * RW, z.H * RH);

    private void SetPx(Zone z, double l, double t, double r, double b)
    {
        z.X = Math.Round(l / RW, 4); z.Y = Math.Round(t / RH, 4);
        z.W = Math.Round((r - l) / RW, 4); z.H = Math.Round((b - t) / RH, 4);
    }

    private bool IsShown(Zone z) => !(Simple && z.Kind == ZoneKind.Peek);
    private bool EffectivePeek(Zone z) => z.Kind == ZoneKind.Peek && !Simple;

    private (List<double> xs, List<double> ys) EdgeCandidates(Zone self)
    {
        var xs = new List<double> { 0, RW };
        var ys = new List<double> { 0, RH };
        foreach (var z in Zs.Where(z => z != self && IsShown(z)))
        {
            var r = Px(z);
            if (z.Kind == ZoneKind.Peek) { xs.Add(r.Right); continue; }
            xs.Add(r.X); xs.Add(r.Right); ys.Add(r.Y); ys.Add(r.Bottom);
        }
        return (xs, ys);
    }

    private static double Snap1(double v, List<double> cands, bool free = false)
    {
        if (free) return Math.Round(v);
        double? best = null;
        foreach (var c in cands) if (Math.Abs(c - v) <= SnapDist && (best == null || Math.Abs(c - v) < Math.Abs(best.Value - v))) best = c;
        return best ?? Math.Round(v / Grid) * Grid;
    }

    // ------------------------------------------------------------------ canvas

    private static readonly string[] HandleNames = { "n", "s", "e", "w", "ne", "nw", "se", "sw" };

    private static Point HandlePoint(RectD r, string h) => new(
        h.Contains('e') ? r.Right : h.Contains('w') ? r.X : r.CenterX,
        h.Contains('s') ? r.Bottom : h.Contains('n') ? r.Y : r.CenterY);

    private IEnumerable<string> HandlesFor(Zone z) => z.Kind == ZoneKind.Peek ? new[] { "e" } : HandleNames;

    private void DrawCanvas()
    {
        _canvas.Children.Clear();
        var sc = 1 / ViewScale;   // 1 screen pixel in canvas units
        foreach (var z in Zs.Where(IsShown).OrderBy(z => z.Kind == ZoneKind.Peek ? 1 : 0))
        {
            var r = Px(z);
            var sel = z.Id == _selId;
            var kind = Simple ? ZoneKind.Zone : z.Kind;
            var amber = Color.FromRgb(0xF5, 0xA5, 0x24);
            var panel = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            panel.Children.Add(Label(z.Name, 15 * sc, FontWeights.Bold));
            panel.Children.Add(Label(kind == ZoneKind.Peek ? $"{(int)r.W} px visible" : $"{(int)r.W} × {(int)r.H}", 11 * sc, FontWeights.Normal));
            panel.Children.Add(Label((kind == ZoneKind.Stack ? $"stack · {z.Stack.ToString().ToLowerInvariant()}" : kind.ToString().ToLowerInvariant()) + (z.Free && !Simple ? " · free" : ""), 10 * sc, FontWeights.Normal));
            var box = new Border
            {
                Width = Math.Max(1, r.W), Height = Math.Max(1, r.H), CornerRadius = new CornerRadius(6 * sc),
                Background = new SolidColorBrush(Color.FromArgb((byte)(sel ? 0x40 : 0x1C), amber.R, amber.G, amber.B)),
                BorderBrush = new SolidColorBrush(Color.FromArgb((byte)(sel ? 0xFF : 0x99), amber.R, amber.G, amber.B)),
                BorderThickness = new Thickness(2 * sc), Child = panel, IsHitTestVisible = false,
            };
            Canvas.SetLeft(box, r.X); Canvas.SetTop(box, r.Y);
            _canvas.Children.Add(box);
        }
        var s = Sel;
        if (s != null && IsShown(s))
        {
            var r = Px(s);
            foreach (var h in HandlesFor(s))
            {
                var p = HandlePoint(r, h);
                var d = 14 * sc;
                var dot = new Ellipse { Width = d, Height = d, Fill = Ui.Tape, Stroke = Ui.Brush("#1B1305"), StrokeThickness = 2 * sc, IsHitTestVisible = false };
                Canvas.SetLeft(dot, p.X - d / 2); Canvas.SetTop(dot, p.Y - d / 2);
                _canvas.Children.Add(dot);
            }
        }
    }

    private static TextBlock Label(string text, double size, FontWeight weight) => new()
    {
        Text = text, FontSize = size, FontWeight = weight, Foreground = Ui.Brush("#FBE3B4"), TextAlignment = TextAlignment.Center,
    };

    // ------------------------------------------------------------------ pointer

    private Zone? HitZone(Point p)
    {
        var shown = Zs.Where(IsShown).ToList();
        var ordered = shown.Where(z => z.Kind == ZoneKind.Peek)
            .Concat(Enumerable.Reverse(shown.Where(z => z.Kind != ZoneKind.Peek && z.Free)))
            .Concat(Enumerable.Reverse(shown.Where(z => z.Kind != ZoneKind.Peek && !z.Free)));
        // Prefer the selected zone when overlapping, so it can be grabbed again.
        var s = Sel;
        if (s != null && IsShown(s) && Px(s).Contains(p.X, p.Y)) return s;
        return ordered.FirstOrDefault(z => Px(z).Contains(p.X, p.Y));
    }

    private void OnDown(object sender, MouseButtonEventArgs e)
    {
        var p = e.GetPosition(_canvas);
        string? handle = null;
        var s = Sel;
        if (s != null && IsShown(s))
        {
            var reach = 12 / ViewScale;
            var r = Px(s);
            handle = HandlesFor(s).FirstOrDefault(h => { var hp = HandlePoint(r, h); return Math.Abs(hp.X - p.X) <= reach && Math.Abs(hp.Y - p.Y) <= reach; });
        }
        var z = handle != null ? s : HitZone(p);
        if (z == null) return;
        if (_selId != z.Id) { _selId = z.Id; BuildSide(); }
        if (z.Kind == ZoneKind.Peek && handle == null) { DrawCanvas(); return; }  // peek docks only resize
        _dragZone = z; _dragHistoryRecorded = false;
        _dragHandle = handle ?? "move"; _dragStart = p; _dragOrig = Px(z);
        _canvas.CaptureMouse();
        DrawCanvas();
    }

    private void OnMove(object sender, MouseEventArgs e)
    {
        var z = _dragZone;
        if (z == null || !_canvas.IsMouseCaptured) return;
        var p = e.GetPosition(_canvas);
        double dx = p.X - _dragStart.X, dy = p.Y - _dragStart.Y;
        var o = _dragOrig;
        var (xs, ys) = EdgeCandidates(z);

        if (z.Kind == ZoneKind.Peek)
        {
            var vis = Math.Clamp(Math.Round((o.W + dx) / 10) * 10, 120, z.PeekWindowWidth * RW - 100);
            z.W = Math.Round(vis / RW, 4);
        }
        else if (_dragHandle == "move")
        {
            double nx = o.X + dx, ny = o.Y + dy;
            var fr = z.Free && !Simple;
            double sl = Snap1(nx, xs, fr), sr = Snap1(nx + o.W, xs, fr) - o.W, st = Snap1(ny, ys, fr), sb = Snap1(ny + o.H, ys, fr) - o.H;
            var x = Math.Clamp(Math.Abs(sl - nx) <= Math.Abs(sr - nx) ? sl : sr, 0, RW - o.W);
            var y = Math.Clamp(Math.Abs(st - ny) <= Math.Abs(sb - ny) ? st : sb, 0, RH - o.H);
            var cur = Px(z);
            // Plain zones may not be dragged over each other; slide along the obstacle instead of stopping dead.
            if (Blocked(z, new RectD(x, y, o.W, o.H)))
            {
                if (!Blocked(z, new RectD(x, cur.Y, o.W, o.H))) y = cur.Y;
                else if (!Blocked(z, new RectD(cur.X, y, o.W, o.H))) x = cur.X;
                else { x = cur.X; y = cur.Y; }
            }
            SetPx(z, x, y, x + o.W, y + o.H);
        }
        else
        {
            double l = o.X, t = o.Y, r = o.Right, b = o.Bottom;
            var h = _dragHandle!;
            var fr = z.Free && !Simple;
            if (h.Contains('e')) r = Math.Clamp(Snap1(r + dx, xs, fr), l + MinSize, RW);
            if (h.Contains('w')) l = Math.Clamp(Snap1(l + dx, xs, fr), 0, r - MinSize);
            if (h.Contains('s')) b = Math.Clamp(Snap1(b + dy, ys, fr), t + MinSize, RH);
            if (h.Contains('n')) t = Math.Clamp(Snap1(t + dy, ys, fr), 0, b - MinSize);
            if (!Blocked(z, new RectD(l, t, r - l, b - t))) SetPx(z, l, t, r, b);
        }
        Touch(redrawSide: false);
    }

    private static bool Overlaps(RectD a, RectD b) => a.X < b.Right - 1 && a.Right > b.X + 1 && a.Y < b.Bottom - 1 && a.Bottom > b.Y + 1;

    /// <summary>Plain zones may not newly overlap other plain zones. Free zones (and peek docks) are exempt.</summary>
    private bool Blocked(Zone z, RectD cand)
    {
        if ((z.Free && !Simple) || z.Kind == ZoneKind.Peek) return false;
        var now = Px(z);
        return Zs.Any(q => q != z && IsShown(q) && q.Kind != ZoneKind.Peek && !(q.Free && !Simple) && Overlaps(cand, Px(q)) && !Overlaps(now, Px(q)));
    }

    private void OnUp(object sender, MouseButtonEventArgs e)
    {
        if (_dragZone == null) return;
        _dragZone = null;
        _dragHistoryRecorded = false;
        _canvas.ReleaseMouseCapture();
        BuildSide();
    }

    // ------------------------------------------------------------------ editing state

    // Capture the previous complete draft after each edit. A mouse drag counts
    // as a single undo step even though it produces many continuous updates.
    private void Touch(bool redrawSide = true)
    {
        var current = ZoneJson.Serialize(_layouts);
        if (current != _lastLayoutState)
        {
            if (_dragZone == null || !_dragHistoryRecorded)
            {
                _undo.Push((_lastLayoutState, _lastSelection, _lastMonitor));
                if (_undo.Count > 80)
                {
                    var newest = _undo.ToArray().Take(80).Reverse().ToArray();
                    _undo.Clear();
                    foreach (var entry in newest) _undo.Push(entry);
                }
            }
            if (_dragZone != null) _dragHistoryRecorded = true;
            _lastLayoutState = current;
            _lastSelection = _selId;
            _lastMonitor = _monitor;
        }
        _dirty = current != _savedLayoutState;
        Title = $"OmniZones editor · {L.Name}" + (_dirty ? " *" : "");
        _status.Text = _dirty ? "Unsaved changes" : "No unsaved changes";
        _undoButton.IsEnabled = _undo.Count > 0;
        DrawCanvas();
        if (redrawSide) BuildSide();
    }

    private void Undo()
    {
        if (!_undo.TryPop(out var state)) return;
        _layouts = ZoneJson.Deserialize<List<ZoneLayout>>(state.layouts)!;
        _monitor = state.monitor;
        if (CurMon is { } m) _ref = m.Work;
        _canvas.Width = RW; _canvas.Height = RH;
        _selId = state.selection;
        _lastLayoutState = ZoneJson.Serialize(_layouts);
        _lastSelection = _selId;
        _lastMonitor = _monitor;
        _dirty = _lastLayoutState != _savedLayoutState;
        _undoButton.IsEnabled = _undo.Count > 0;
        Title = $"OmniZones editor · {L.Name}" + (_dirty ? " *" : "");
        _status.Text = _dirty ? "Unsaved changes" : "Undone";
        UpdateCaption(); BuildSide(); DrawCanvas();
    }

    private void Save()
    {
        var copy = Clone(L);
        _rt.Store.Update(s =>
        {
            var i = s.Layouts.FindIndex(l => l.Id == _layoutId);
            if (i >= 0) s.Layouts[i] = copy;
        });
        _dirty = false;
        _undo.Clear();
        _lastLayoutState = _savedLayoutState = ZoneJson.Serialize(_layouts);
        _lastSelection = _selId;
        _lastMonitor = _monitor;
        _undoButton.IsEnabled = false;
        Title = $"OmniZones editor · {L.Name}";
        _status.Text = "Saved";
    }

    private void Revert()
    {
        _layouts = _rt.Store.Current.Layouts.Select(Clone).ToList();
        _selId = Zs.FirstOrDefault()?.Id;
        _undo.Clear();
        _lastLayoutState = _savedLayoutState = ZoneJson.Serialize(_layouts);
        _lastSelection = _selId;
        _lastMonitor = _monitor;
        _undoButton.IsEnabled = false;
        _dirty = false;
        Title = $"OmniZones editor · {L.Name}";
        _status.Text = "Reverted";
        BuildSide(); DrawCanvas();
    }

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (!_dirty) return;
        var answer = MessageBox.Show(this, "Save your zone changes?", "OmniZones editor", MessageBoxButton.YesNoCancel, MessageBoxImage.Question);
        if (answer == MessageBoxResult.Cancel) e.Cancel = true;
        else if (answer == MessageBoxResult.Yes) Save();
    }

    private static string NewId() => "z" + (DateTime.UtcNow.Ticks % 100000000).ToString();

    // ------------------------------------------------------------------ side panel

    private void BuildSide()
    {
        _side.Children.Clear();
        var simple = Simple;

        // --- which profile and which monitor are being edited
        var head = Ui.Text($"Profile: {L.Name}", 18, bold: true);
        head.Margin = new Thickness(0, 0, 0, 2);
        _side.Children.Add(head);
        _side.Children.Add(Ui.Text("Only this profile can be edited here. Switch profiles in the OmniZones settings window.", 12, muted: true));
        BuildMonitorSection();
        if (simple)
            _side.Children.Add(Ui.CardOf(Ui.Text("Simple mode is on: only plain zones are used. Stacks, peek docks, scenes, free zones and app preferences are hidden. Turn it off in the settings to edit them.", 12, muted: true)));

        // --- profile name / key / gap
        _side.Children.Add(Ui.Heading("Profile"));
        var nameRow = Ui.Row(Ui.Text("Name ", 13), Field(L.Name, v => { if (v.Length > 0) { L.Name = v; Touch(); } }, 160));
        if (!simple)
        {
            nameRow.Children.Add(Ui.Text("  Key ", 13));
            nameRow.Children.Add(Field(L.Key, v => { L.Key = v.Trim().Length > 0 ? v.Trim()[..1] : ""; Touch(); }, 36));
        }
        _side.Children.Add(nameRow);
        var gap = new Slider { Minimum = 0, Maximum = 40, TickFrequency = 2, IsSnapToTickEnabled = true, Value = L.Gap, Width = 200 };
        var gapLabel = Ui.Text($"{L.Gap} px", 13);
        gap.ValueChanged += (_, _) => { L.Gap = (int)gap.Value; gapLabel.Text = $"{L.Gap} px"; Touch(redrawSide: false); };
        _side.Children.Add(Ui.Row(Ui.Text("Gap ", 13), gap, gapLabel));
        if (!simple) _side.Children.Add(Ui.Hint("Win+Alt+<key> applies this profile and sends windows to their zones."));

        // --- zones
        _side.Children.Add(Ui.Heading("Zones"));
        foreach (var z in Zs.Where(IsShown))
        {
            var zone = z;
            var label = $"{z.Name}   ({(Simple ? "zone" : z.Kind.ToString().ToLowerInvariant())})";
            var b = Ui.Button(label, () => { _selId = zone.Id; BuildSide(); DrawCanvas(); });
            b.HorizontalContentAlignment = HorizontalAlignment.Left;
            b.Margin = new Thickness(0, 2, 0, 2);
            if (z.Id == _selId) { b.Background = Ui.Selected; b.BorderBrush = Ui.Accent; }
            _side.Children.Add(Ui.Row(b, Ui.Button("Remove", () => DeleteZone(zone))));
        }
        _side.Children.Add(Ui.Row(Ui.Button("+ Add zone", AddZone)));

        var s = Sel;
        if (s != null && IsShown(s)) BuildZoneSection(s, simple);
        if (!simple && IsMain) BuildScenes();
    }

    private void BuildMonitorSection()
    {
        _side.Children.Add(Ui.Heading("Monitor to configure"));
        if (_mons.Count > 1)
        {
            var combo = Ui.Combo(_mons.Select(MonitorName), i => SelectMonitor(_mons[i].Device), 300);
            combo.SelectedIndex = Math.Max(0, _mons.FindIndex(m => m.Device == _monitor));
            _side.Children.Add(Ui.Row(combo));
        }
        else _side.Children.Add(Ui.Text(MonitorName(CurMon), 13));
        _side.Children.Add(Ui.Hint(IsMain
            ? "Main monitor defaults apply unless this physical screen has its own override."
            : L.Id.Equals("full", StringComparison.OrdinalIgnoreCase)
                ? "This Full profile covers the entire display with one zone. You can customize this monitor independently."
                : "Secondary monitors use Halves until you customize them. Hardware-specific overrides follow a screen across DisplayFusion arrangements."));
        if (IsMain && ExistingKey == null && ProfileKey.Length > 0)
            _side.Children.Add(Ui.Button("Customize this physical display", () =>
            {
                L.Monitors[ProfileKey] = L.Zones.Select(z => ZoneJson.Deserialize<Zone>(ZoneJson.Serialize(z))!).ToList();
                Touch();
            }));
    }

    private void BuildZoneSection(Zone z, bool simple)
    {
        _side.Children.Add(Ui.Heading($"Zone · {z.Name}"));
        _side.Children.Add(Ui.Row(Ui.Text("Name ", 13), Field(z.Name, v => { if (v.Length > 0) { z.Name = v; Touch(); } }, 220)));

        if (!simple)
        {
            var type = Ui.Combo(new[] { "Zone", "Stack", "Peek (edge dock)" }, i => ChangeKind(z, (ZoneKind)i), 170);
            type.SelectedIndex = (int)z.Kind;
            _side.Children.Add(Ui.Row(Ui.Text("Type ", 13), type));
            _side.Children.Add(Ui.Hint(z.Kind switch
            {
                ZoneKind.Stack => "Several windows share the zone, split into rows or an accordion.",
                ZoneKind.Peek => "A window docks off the left edge and slides in when you hover it.",
                _ => "One window fills the zone.",
            }));
        }

        if (!simple && z.Kind != ZoneKind.Peek)
        {
            var free = Ui.Check("Free zone", v => { z.Free = v; Touch(); }, hasHintBelow: true);
            free.IsChecked = z.Free;
            _side.Children.Add(free);
            _side.Children.Add(Ui.Hint("Placed exactly as drawn: no snapping while editing, may overlap other zones or leave gaps, and the layout gap is not applied."));
        }

        if (!simple && z.Kind == ZoneKind.Stack)
        {
            var style = Ui.Combo(new[] { "Rows (equal split)", "Accordion (front window big)" }, i => { z.Stack = i == 0 ? StackStyle.Rows : StackStyle.Accordion; Touch(); }, 220);
            style.SelectedIndex = (int)z.Stack;
            _side.Children.Add(Ui.Row(Ui.Text("Style ", 13), style));
        }

        if (!simple && z.Kind == ZoneKind.Peek)
        {
            _side.Children.Add(Ui.Row(Ui.Text("Visible ", 13), Field(((int)(z.W * RW)).ToString(), v =>
            {
                if (double.TryParse(v, out var px)) { z.W = Math.Round(Math.Clamp(px, 120, z.PeekWindowWidth * RW - 100) / RW, 4); Touch(); }
            }, 70), Ui.Text(" px   Window width ", 13), Field(((int)(z.PeekWindowWidth * RW)).ToString(), v =>
            {
                if (double.TryParse(v, out var px)) { z.PeekWindowWidth = Math.Round(Math.Clamp(px, 400, RW * 0.9) / RW, 4); Touch(); }
            }, 70)));
        }
        else
        {
            var r = Px(z);
            TextBox G(double v, Action<double> set) => Field(((int)Math.Round(v)).ToString(), s =>
            {
                if (double.TryParse(s, out var n)) { set(n); Touch(); }
            }, 62);
            _side.Children.Add(Ui.Text("Position and size (px)", 12, muted: true));
            _side.Children.Add(Ui.Row(
                G(r.X, n => SetPx(z, Math.Clamp(n, 0, RW - r.W), r.Y, Math.Clamp(n, 0, RW - r.W) + r.W, r.Bottom)),
                G(r.Y, n => SetPx(z, r.X, Math.Clamp(n, 0, RH - r.H), r.Right, Math.Clamp(n, 0, RH - r.H) + r.H)),
                G(r.W, n => SetPx(z, r.X, r.Y, r.X + Math.Clamp(n, MinSize, RW - r.X), r.Bottom)),
                G(r.H, n => SetPx(z, r.X, r.Y, r.Right, r.Y + Math.Clamp(n, MinSize, RH - r.Y)))));
            _side.Children.Add(Ui.Text("x            y            width       height", 11, muted: true));
        }

        if (!simple)
        {
            _side.Children.Add(Ui.Text("Preferred apps", 12, muted: true));
            _side.Children.Add(Field(string.Join(", ", z.Apps), v =>
            {
                z.Apps = v.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Select(a => a.ToLowerInvariant().Replace(".exe", "")).Distinct().ToList();
                Touch(redrawSide: false);
            }));
            var running = WindowTools.RunningAppNames();
            var add = new ComboBox { Width = 200, MinHeight = 28, Margin = new Thickness(0, 4, 0, 0), HorizontalAlignment = HorizontalAlignment.Left };
            add.Items.Add("Add a running app…");
            foreach (var n in running) add.Items.Add(n);
            add.SelectedIndex = 0;
            add.SelectionChanged += (_, _) =>
            {
                if (add.SelectedIndex > 0 && add.SelectedItem is string name && !z.Apps.Contains(name)) { z.Apps.Add(name); Touch(); }
            };
            _side.Children.Add(add);
            _side.Children.Add(Ui.Hint("Applying this layout sends these apps here first (process names, e.g. firefox, obsidian)."));
        }

        var buttons = new WrapPanel { Margin = new Thickness(0, 6, 0, 0) };
        if (z.Kind != ZoneKind.Peek || simple)
        {
            buttons.Children.Add(Ui.Button("Split side by side", () => Split(z, vertical: true)));
            buttons.Children.Add(Ui.Button("Split top / bottom", () => Split(z, vertical: false)));
        }
        buttons.Children.Add(Ui.Button("Delete zone", () => DeleteZone(z)));
        _side.Children.Add(buttons);
    }

    private void BuildScenes()
    {
        _side.Children.Add(Ui.Heading("Scenes"));
        _side.Children.Add(Ui.Text("Dropping an app on a zone can switch to another layout and place it there.", 12, muted: true));
        foreach (var rule in L.Scenes.ToList())
        {
            var r = rule;
            var onZone = Ui.Combo(L.Zones.Select(z => z.Name), i => { r.OnZoneId = L.Zones[i].Id; Touch(redrawSide: false); }, 120);
            onZone.SelectedIndex = Math.Max(0, L.Zones.FindIndex(z => z.Id == r.OnZoneId));
            var others = _layouts.Where(l => l.Id != L.Id).ToList();
            var target = Ui.Combo(others.Select(l => l.Name), i => { r.SwitchToLayoutId = others[i].Id; r.PlaceInZoneId = others[i].Zones.FirstOrDefault()?.Id ?? ""; Touch(); }, 120);
            target.SelectedIndex = others.FindIndex(l => l.Id == r.SwitchToLayoutId);
            var tl = _layouts.FirstOrDefault(l => l.Id == r.SwitchToLayoutId);
            var place = Ui.Combo((tl?.Zones ?? new()).Select(z => z.Name), i => { r.PlaceInZoneId = tl!.Zones[i].Id; Touch(redrawSide: false); }, 120);
            place.SelectedIndex = tl == null ? -1 : Math.Max(0, tl.Zones.FindIndex(z => z.Id == r.PlaceInZoneId));
            var card = new StackPanel();
            card.Children.Add(Ui.Row(Ui.Text("When ", 13), Field(r.Process, v => { r.Process = v.Trim().ToLowerInvariant().Replace(".exe", ""); Touch(redrawSide: false); }, 110), Ui.Text(" is dropped on ", 13), onZone));
            card.Children.Add(Ui.Row(Ui.Text("switch to ", 13), target, Ui.Text(" and put it in ", 13), place));
            card.Children.Add(Ui.Row(Ui.Button("Remove rule", () => { L.Scenes.Remove(r); Touch(); })));
            var wrap = Ui.CardOf(card);
            wrap.Margin = new Thickness(0, 4, 0, 4);
            _side.Children.Add(wrap);
        }
        if (_layouts.Count > 1)
            _side.Children.Add(Ui.Row(Ui.Button("Add scene rule", () =>
            {
                var other = _layouts.First(l => l.Id != L.Id);
                L.Scenes.Add(new SceneRule { Process = "", OnZoneId = L.Zones.FirstOrDefault()?.Id ?? "", SwitchToLayoutId = other.Id, PlaceInZoneId = other.Zones.FirstOrDefault()?.Id ?? "" });
                Touch();
            })));
    }

    private static TextBox Field(string value, Action<string> commit, double width = double.NaN)
    {
        var t = Ui.Field(commit, width);
        t.Text = value;
        return t;
    }

    // ------------------------------------------------------------------ operations

    private void ChangeKind(Zone z, ZoneKind k)
    {
        if (z.Kind == k) return;
        if (k == ZoneKind.Peek && Zs.Any(q => q.Kind == ZoneKind.Peek && q != z)) { _status.Text = "A layout can have only one peek zone."; BuildSide(); return; }
        if (k == ZoneKind.Peek) { z.X = 0; z.W = Math.Round(300 / RW, 4); z.PeekWindowWidth = Math.Round(Math.Min(1100, RW * 0.6) / RW, 4); }
        if (z.Kind == ZoneKind.Peek) { z.X = 0; z.W = Math.Max(z.W, 0.18); }
        z.Kind = k;
        Touch();
    }

    private void AddZone()
    {
        var z = new Zone { Id = NewId(), Name = $"Zone {Zs.Count + 1}", X = 0.3, Y = 0.25, W = 0.4, H = 0.5 };
        Zs.Add(z); _selId = z.Id; Touch();
    }

    private void DeleteZone(Zone z)
    {
        var shared = ReferenceEquals(Zs, L.Zones);
        Zs.Remove(z);
        if (shared)
        {
            L.Scenes.RemoveAll(r => r.OnZoneId == z.Id);
            foreach (var l in _layouts) l.Scenes.RemoveAll(r => r.SwitchToLayoutId == L.Id && r.PlaceInZoneId == z.Id);
        }
        _selId = Zs.FirstOrDefault()?.Id;
        Touch();
    }

    private void Split(Zone z, bool vertical)
    {
        var r = Px(z);
        var nz = new Zone { Id = NewId(), Name = z.Name + " 2", Kind = ZoneKind.Zone };
        if (vertical)
        {
            var half = Math.Round(r.W / 2 / Grid) * Grid;
            SetPx(nz, r.X + half, r.Y, r.Right, r.Bottom);
            SetPx(z, r.X, r.Y, r.X + half, r.Bottom);
        }
        else
        {
            var half = Math.Round(r.H / 2 / Grid) * Grid;
            SetPx(nz, r.X, r.Y + half, r.Right, r.Bottom);
            SetPx(z, r.X, r.Y, r.Right, r.Y + half);
        }
        Zs.Add(nz); _selId = nz.Id; Touch();
    }
}
