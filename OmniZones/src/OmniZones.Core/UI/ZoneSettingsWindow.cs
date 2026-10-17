using System.Windows;
using System.Windows.Controls;
using OmniZones.Model;
using OmniZones.Runtime;

namespace OmniZones.UI;

/// <summary>The tray icon's settings screen. Every change is saved and applied immediately.</summary>
internal sealed class ZoneSettingsWindow : Window
{
    private readonly ZoneRuntime _rt;
    private bool _loading;

    private readonly CheckBox _enabled, _simple, _shift, _arrows, _restore, _autostart;
    private readonly ComboBox _occupied, _hold, _layout;
    private readonly StackPanel _advanced = new(), _fullDragging = new(), _keyboard = new(), _profileTools = new() { Orientation = Orientation.Horizontal };
    private readonly TextBlock _shiftHint;

    public ZoneSettingsWindow(ZoneRuntime rt)
    {
        _rt = rt;
        Title = "OmniZones settings";
        Width = 500; Height = 740; MinWidth = 420;
        Ui.Theme(this);
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        FontFamily = new System.Windows.Media.FontFamily("Segoe UI");

        _enabled = Ui.Check("OmniZones is on", v => Edit(s => s.Enabled = v));
        _simple = Ui.Check("Simple mode", v => Edit(s => s.SimpleMode = v), hasHintBelow: true);
        _shift = Ui.Check("Require holding Shift to snap", v => Edit(s => s.RequireShift = v), hasHintBelow: true);
        _shiftHint = Ui.Hint("");
        _occupied = Ui.Combo(new[] { "Swap places", "Stack on top" }, i => Edit(s => s.OccupiedDefault = i == 0 ? OccupiedAction.Swap : OccupiedAction.Stack));
        _hold = Ui.Combo(new[] { "Ctrl", "Alt", "Nothing (off)" }, i => Edit(s => s.OccupiedHold = i switch { 0 => HoldKey.Ctrl, 1 => HoldKey.Alt, _ => HoldKey.None }), 130);
        _arrows = Ui.Check("Win+arrows move windows between zones", v => Edit(s => s.OverrideWinArrows = v), hasHintBelow: true);
        _restore = Ui.Check("Restore a window's own size when dragged out of a zone", v => Edit(s => s.RestoreSizeOnUnsnap = v));
        _layout = Ui.Combo(Enumerable.Empty<string>(), i =>
        {
            var id = _loading ? null : _rt.Store.Current.Layouts.ElementAtOrDefault(i)?.Id;
            if (id != null) _rt.Dispatcher.BeginInvoke(new Action(() => _rt.Manager.ApplyLayout(id)));
        }, 220);
        _autostart = Ui.Check("Start OmniZones with Windows", v => _rt.SetAutostart?.Invoke(v));

        var root = new StackPanel { Margin = new Thickness(22, 18, 22, 18) };
        root.Children.Add(Ui.Text("OmniZones", 22, bold: true));
        root.Children.Add(Ui.Text("Snap windows into zones by dragging them. Changes apply instantly.", 12, muted: true));

        root.Children.Add(Ui.Heading("General"));
        root.Children.Add(_enabled);
        if (_rt.SetAutostart != null) root.Children.Add(_autostart);
        root.Children.Add(_simple);
        root.Children.Add(Ui.Hint("Keeps it simple: plain zones only, and a window dropped on a taken zone stacks on top. Turns off scenes, stacks, peek docks, free zones, per-app preferences and extra hotkeys."));

        root.Children.Add(Ui.Heading("Dragging"));
        root.Children.Add(_shift);
        root.Children.Add(_shiftHint);
        _fullDragging.Children.Add(Ui.Text("When you drop a window on a zone that already has one:", 13));
        _fullDragging.Children.Add(Ui.Row(_occupied));
        _fullDragging.Children.Add(Ui.Row(Ui.Text("Hold  ", 13), _hold, Ui.Text("  while dropping to do the opposite", 13)));
        _fullDragging.Children.Add(Ui.Hint("Swap sends the other window to where yours came from. Stack puts yours on top of it. Hold Alt over a stack zone to swap with one of its windows instead of joining it."));
        _fullDragging.Children.Add(_restore);
        root.Children.Add(_fullDragging);

        _keyboard.Children.Add(Ui.Heading("Keyboard"));
        _keyboard.Children.Add(_arrows);
        _keyboard.Children.Add(Ui.Hint("Win+←/→ move between zones, Win+↑ fills the screen, Win+↓ unsnaps. Win+Ctrl+Z undoes the last arrangement."));
        root.Children.Add(_keyboard);

        root.Children.Add(Ui.Heading("Profile"));
        root.Children.Add(Ui.Row(Ui.Text("Active profile  ", 13), _layout));
        root.Children.Add(Ui.Hint("A profile is a set of zones for each of your monitors. The editor changes only the profile selected here."));
        _profileTools.Children.Add(Ui.Button("New", NewProfile));
        _profileTools.Children.Add(Ui.Button("Copy", CopyProfile));
        _profileTools.Children.Add(Ui.Button("Delete", DeleteProfile));
        root.Children.Add(_profileTools);
        root.Children.Add(Ui.Row(Ui.Button("Edit this profile's zones…", _rt.OpenEditor, primary: true)));

        _advanced.Children.Add(Ui.Heading("Advanced hotkeys (off in simple mode)"));
        _advanced.Children.Add(Ui.Hint("Win+Alt+<layout key> applies a layout · Win+Alt+↓/↑ cycles a stack · Win+Alt+T gathers terminals into the stack · Win+` peeks the docked window."));
        root.Children.Add(_advanced);

        Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = root };

        _rt.Store.Changed += OnChanged;
        Closed += (_, _) => _rt.Store.Changed -= OnChanged;
        Reload();
    }

    private void NewProfile()
    {
        var id = "l" + (DateTime.UtcNow.Ticks % 100000000);
        _rt.Store.Update(c =>
        {
            c.Layouts.Add(new ZoneLayout { Id = id, Name = $"Profile {c.Layouts.Count + 1}", Gap = 0, Zones = DefaultLayouts.NewMainZones() });
        });
        _rt.Dispatcher.BeginInvoke(new Action(() => _rt.Manager.ApplyLayout(id)));
    }

    private void CopyProfile()
    {
        var id = "l" + (DateTime.UtcNow.Ticks % 100000000);
        _rt.Store.Update(c =>
        {
            var copy = ZoneJson.Deserialize<ZoneLayout>(ZoneJson.Serialize(c.ActiveLayout))!;
            copy.Id = id; copy.Name += " copy"; copy.Key = "";
            c.Layouts.Add(copy);
        });
        _rt.Dispatcher.BeginInvoke(new Action(() => _rt.Manager.ApplyLayout(id)));
    }

    private void DeleteProfile()
    {
        var cur = _rt.Store.Current;
        if (cur.Layouts.Count <= 1) { MessageBox.Show(this, "Keep at least one profile.", "OmniZones"); return; }
        if (MessageBox.Show(this, $"Delete the profile \"{cur.ActiveLayout.Name}\"?", "OmniZones", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        var gone = cur.ActiveLayoutId;
        string next = "";
        _rt.Store.Update(c =>
        {
            c.Layouts.RemoveAll(l => l.Id == gone);
            foreach (var l in c.Layouts) l.Scenes.RemoveAll(r => r.SwitchToLayoutId == gone);
            next = c.Layouts[0].Id;
        });
        _rt.Dispatcher.BeginInvoke(new Action(() => _rt.Manager.ApplyLayout(next)));
    }

    private void OnChanged() => Dispatcher.BeginInvoke(new Action(Reload));

    private void Edit(Action<ZoneSettings> edit)
    {
        if (_loading) return;
        _rt.Store.Update(edit);
        Reload();
    }

    private void Reload()
    {
        _loading = true;
        try
        {
            var s = _rt.Store.Current;
            _enabled.IsChecked = s.Enabled;
            _simple.IsChecked = s.SimpleMode;
            _shift.IsChecked = s.RequireShift;
            _shiftHint.Text = s.RequireShift
                ? "On: dragging a window moves it normally. Hold Shift while dragging to show the zones and snap."
                : "Off: zones appear whenever you drag a window. Hold Shift while dragging to move it freely instead.";
            _occupied.SelectedIndex = s.OccupiedDefault == OccupiedAction.Swap ? 0 : 1;
            _hold.SelectedIndex = s.OccupiedHold switch { HoldKey.Ctrl => 0, HoldKey.Alt => 1, _ => 2 };
            _arrows.IsChecked = s.OverrideWinArrows;
            _restore.IsChecked = s.RestoreSizeOnUnsnap;
            var vis = s.SimpleMode ? Visibility.Collapsed : Visibility.Visible;
            _advanced.Visibility = vis; _fullDragging.Visibility = vis; _keyboard.Visibility = vis; _profileTools.Visibility = vis;
            if (_rt.GetAutostart != null) _autostart.IsChecked = _rt.GetAutostart();
            _layout.Items.Clear();
            foreach (var l in s.Layouts) _layout.Items.Add(s.SimpleMode || string.IsNullOrEmpty(l.Key) ? l.Name : $"{l.Name}  (Win+Alt+{l.Key.ToUpperInvariant()})");
            _layout.SelectedIndex = Math.Max(0, s.Layouts.FindIndex(l => l.Id == s.ActiveLayoutId));
        }
        finally { _loading = false; }
    }
}
