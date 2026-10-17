using System;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using OmniSync.Hub.Infrastructure.Services;
using Button = System.Windows.Controls.Button;
using TextBox = System.Windows.Controls.TextBox;
using ListBoxItem = System.Windows.Controls.ListBoxItem;
using MenuItem = System.Windows.Controls.MenuItem;
using ContextMenu = System.Windows.Controls.ContextMenu;
using MessageBox = System.Windows.MessageBox;
using Point = System.Windows.Point;
using Color = System.Windows.Media.Color;
using KeyEventArgs = System.Windows.Input.KeyEventArgs;
using MouseEventArgs = System.Windows.Input.MouseEventArgs;
using DragEventArgs = System.Windows.DragEventArgs;
using DragDropEffects = System.Windows.DragDropEffects;
using DataObject = System.Windows.DataObject;

namespace OmniSync.Hub.Presentation;

public partial class OmniSweepWindow : Window
{
    private readonly OmniSweepViewModel _viewModel;
    private readonly KeyboardHook? _keyboard;
    private readonly Action<string>? _focusLog;
    private bool _interaction;
    private bool _closing;
    private bool _closed;
    private bool _closeQueued;
    private bool _dragged;
    private bool _activationReady;
    private bool _modifierClosePending;
    private Point _dragStart;
    private Project? _dragSetup;
    private Project? _renamingSetup;
    private TextBox? _renameEditor;
    private bool _skipNextTileMouseUp;
    private readonly System.Windows.Threading.DispatcherTimer _foregroundWatch =
        new() { Interval = TimeSpan.FromMilliseconds(120) };

    private const int VkShift = 0x10;
    private const int VkControl = 0x11;
    private const int VkMenu = 0x12;
    private const int VkLWin = 0x5B;
    private const int VkRWin = 0x5C;

    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern short GetAsyncKeyState(int vKey);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, IntPtr processId);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint attach, uint attachTo, bool attachInput);
    [DllImport("user32.dll")] private static extern bool BringWindowToTop(IntPtr hwnd);

    private static bool AnyModifierDown() =>
        (GetAsyncKeyState(VkShift) & 0x8000) != 0 ||
        (GetAsyncKeyState(VkControl) & 0x8000) != 0 ||
        (GetAsyncKeyState(VkMenu) & 0x8000) != 0 ||
        (GetAsyncKeyState(VkLWin) & 0x8000) != 0 ||
        (GetAsyncKeyState(VkRWin) & 0x8000) != 0;

    private bool HasForeground()
    {
        var hwnd = new System.Windows.Interop.WindowInteropHelper(this).Handle;
        return hwnd != IntPtr.Zero && GetForegroundWindow() == hwnd;
    }

    private void TraceFocus(string stage)
    {
        if (_focusLog == null) return;
        var hwnd = new System.Windows.Interop.WindowInteropHelper(this).Handle;
        var foreground = GetForegroundWindow();
        _focusLog($"[OmniSweep][Focus] {stage}: active={IsActive}, foreground={hwnd != IntPtr.Zero && foreground == hwnd}, modifiers={AnyModifierDown()}, ready={_activationReady}, interaction={_interaction}");
    }

    private void ForegroundGrid(bool force = false)
    {
        var hwnd = new System.Windows.Interop.WindowInteropHelper(this).Handle;
        if (hwnd == IntPtr.Zero) return;
        // Only borrow the foreground thread's input queue if ordinary activation fails.
        // No synthetic Alt key events: they can interfere with Alt-Tab.
        uint currentThread = GetCurrentThreadId();
        uint foregroundThread = 0;
        if (force)
        {
            var foreground = GetForegroundWindow();
            if (foreground != IntPtr.Zero && foreground != hwnd)
                foregroundThread = GetWindowThreadProcessId(foreground, IntPtr.Zero);
        }
        bool attached = foregroundThread != 0 && foregroundThread != currentThread &&
                        AttachThreadInput(currentThread, foregroundThread, true);
        try
        {
            if (force) BringWindowToTop(hwnd);
            SetForegroundWindow(hwnd);
        }
        finally
        {
            if (attached) AttachThreadInput(currentThread, foregroundThread, false);
        }
        Activate();
        FocusGrid();
    }

    private async System.Threading.Tasks.Task WaitForModifiersReleasedAsync()
    {
        // A stale Alt/Win state must never leave auto-hide permanently disabled.
        var deadline = Environment.TickCount64 + 350;
        while (!_closing && !_closed && AnyModifierDown() && Environment.TickCount64 < deadline)
            await System.Threading.Tasks.Task.Delay(10);
    }

    private async System.Threading.Tasks.Task FinishOpeningAsync()
    {
        // Focus immediately while the hotkey still grants foreground activation rights.
        // Auto-hide stays disabled until its modifiers have been released.
        ForegroundGrid();
        await WaitForModifiersReleasedAsync();
        if (_closing || _closed) return;

        TraceFocus("after-hotkey-release");
        // Alt/key-up can change foreground ownership; restore it once after release.
        if (!HasForeground()) { ForegroundGrid(force: true); TraceFocus("foreground-retry"); }

        await System.Threading.Tasks.Task.Delay(75);
        if (_closing || _closed) return;

        if (!HasForeground()) { ForegroundGrid(force: true); TraceFocus("foreground-retry"); }
        _activationReady = true;
        TraceFocus("opening-armed");
        if (HasForeground()) _foregroundWatch.Start();
        // If Windows still denies foreground ownership, never leave a dead popup visible.
        if (!HasForeground()) { TraceFocus("activation-failed"); QueueClose(); }
    }
    public OmniSweepWindow(OmniSweepViewModel viewModel, KeyboardHook? keyboard = null, Action<string>? focusLog = null)
    {
        InitializeComponent(); _viewModel = viewModel; _keyboard = keyboard; _focusLog = focusLog; DataContext = viewModel;
        if (_keyboard != null) _keyboard.IsSweepActive = true;
        ResultsList.ContextMenu = new ContextMenu();
        viewModel.RequestClose += CloseRequested;
        _foregroundWatch.Tick += (_, _) =>
        {
            if (_activationReady && !_interaction && !_closing && !_closed && !HasForeground())
                CloseForLostFocus();
        };
        Loaded += async (_, _) => { TraceFocus("loaded"); await FinishOpeningAsync(); };
        Activated += (_, _) => TraceFocus("activated");
        Deactivated += (_, _) => TraceFocus("deactivated");
        Closing += (_, _) => { TraceFocus("closing"); _closing = true; };
        Closed += (_, _) =>
        {
            _closed = true;
            _foregroundWatch.Stop();
            viewModel.RequestClose -= CloseRequested; viewModel.Dispose();
            if (_keyboard != null) _keyboard.IsSweepActive = false;
        };
    }
    private void CloseRequested(object? sender, EventArgs e) => QueueClose();
    private void QueueClose()
    {
        if (_closing || _closed || _closeQueued) return;
        _closeQueued = true;
        Dispatcher.BeginInvoke(new Action(() =>
        {
            _closeQueued = false;
            if (!_closing && !_closed) Close();
        }));
    }
    private void FocusGrid()
    {
        ResultsList.Focus();
        if (ResultsList.SelectedItem != null)
        {
            ResultsList.ScrollIntoView(ResultsList.SelectedItem);
            ResultsList.UpdateLayout();
            (ResultsList.ItemContainerGenerator.ContainerFromItem(ResultsList.SelectedItem) as ListBoxItem)?.Focus();
        }
    }
    private void Search_Click(object sender, RoutedEventArgs e) => ShowSearch();
    private void ShowSearch() { SearchInput.Visibility = Visibility.Visible; SearchInput.Focus(); SearchInput.SelectAll(); }
    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (_interaction) return;
        if (e.Key == Key.Escape)
        {
            if (SearchInput.Visibility == Visibility.Visible) { _viewModel.SearchText = ""; SearchInput.Visibility = Visibility.Collapsed; FocusGrid(); }
            else Close();
            e.Handled = true; return;
        }
        if ((e.Key == Key.F && Keyboard.Modifiers == ModifierKeys.Control) ||
            (e.Key == Key.OemQuestion && Keyboard.Modifiers == ModifierKeys.None && !SearchInput.IsKeyboardFocusWithin))
        { ShowSearch(); e.Handled = true; return; }
        if (SearchInput.IsKeyboardFocusWithin)
        {
            if (e.Key == Key.Enter) { _viewModel.ExecuteCommand.Execute(null); e.Handled = true; }
            else if (e.Key == Key.Down) { FocusGrid(); e.Handled = true; }
            return;
        }
        if (ResultsList.IsKeyboardFocusWithin && Keyboard.Modifiers == ModifierKeys.None)
        {
            int columns = Math.Max(1, (int)((ResultsList.ActualWidth - 20) / 234));
            int delta = e.Key switch { Key.W or Key.Up => -columns, Key.S or Key.Down => columns, Key.A or Key.Left => -1, Key.D or Key.Right => 1, _ => 0 };
            if (delta != 0)
            {
                if (ResultsList.Items.Count > 0) ResultsList.SelectedIndex = Math.Clamp(ResultsList.SelectedIndex + delta, 0, ResultsList.Items.Count - 1);
                FocusGrid(); e.Handled = true; return;
            }
            if (e.Key is Key.Space or Key.Enter) { _viewModel.ExecuteCommand.Execute(null); e.Handled = true; return; }
        }
        string shortcut = e.Key >= Key.A && e.Key <= Key.Z ? e.Key.ToString() :
            e.Key >= Key.D0 && e.Key <= Key.D9 ? ((int)e.Key - (int)Key.D0).ToString() :
            e.Key >= Key.NumPad0 && e.Key <= Key.NumPad9 ? ((int)e.Key - (int)Key.NumPad0).ToString() :
            e.Key >= Key.F1 && e.Key <= Key.F24 ? e.Key.ToString() : "";
        if (Keyboard.Modifiers == ModifierKeys.Shift) shortcut = "SHIFT+" + shortcut;
        else if (Keyboard.Modifiers != ModifierKeys.None) return;
        var tile = _viewModel.Results.FirstOrDefault(r => r.Shortcut == shortcut && shortcut.Length > 0);
        if (tile != null) { _viewModel.ExecuteCommand.Execute(tile); e.Handled = true; }
    }
    private void Window_Deactivated(object sender, EventArgs e)
    {
        if (_renameEditor != null) FinishRename(save: true, restoreGridFocus: false);
        CloseForLostFocus();
    }

    private async void CloseForLostFocus()
    {
        if (_interaction || !_activationReady || _closing || _closed || _modifierClosePending) return;

        if (AnyModifierDown())
        {
            _modifierClosePending = true;
            await WaitForModifiersReleasedAsync();
            _modifierClosePending = false;
        }

        if (!_interaction && !_closing && !_closed && !HasForeground())
            QueueClose();
    }

    public async void CloseAfterHotkeyRelease()
    {
        if (_closing || _closed || _modifierClosePending) return;

        _modifierClosePending = true;
        await WaitForModifiersReleasedAsync();
        _modifierClosePending = false;

        if (!_closing && !_closed)
            QueueClose();
    }

    private void EndInteraction()
    {
        if (_renamingSetup != null) return;
        _interaction = false;
        if (!_closing && !_closed && !HasForeground()) CloseForLostFocus();
    }
    private static ListBoxItem? TileAt(DependencyObject? source)
    {
        while (source != null)
        {
            if (source is ListBoxItem item) return item;
            source = source is Visual ? VisualTreeHelper.GetParent(source) : LogicalTreeHelper.GetParent(source);
        }
        return null;
    }
    private void Tile_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (_renamingSetup != null) { _skipNextTileMouseUp = true; _dragSetup = null; return; }
        _dragStart = e.GetPosition(this); _dragged = false;
        _dragSetup = (TileAt(e.OriginalSource as DependencyObject)?.DataContext as OmniSweepItem)?.Data as Project;
    }
    private void Tile_MouseMove(object sender, MouseEventArgs e)
    {
        if (e.LeftButton != MouseButtonState.Pressed || _dragSetup == null || _dragged) return;
        var p = e.GetPosition(this);
        if (Math.Abs(p.X - _dragStart.X) < SystemParameters.MinimumHorizontalDragDistance &&
            Math.Abs(p.Y - _dragStart.Y) < SystemParameters.MinimumVerticalDragDistance) return;
        _dragged = true; _interaction = true;
        try { DragDrop.DoDragDrop(ResultsList, new DataObject("OmniSetup", _dragSetup), DragDropEffects.Move); }
        finally { EndInteraction(); _dragSetup = null; }
    }
    private void Tile_Drop(object sender, DragEventArgs e)
    {
        if (e.Data.GetData("OmniSetup") is Project moving &&
            (TileAt(e.OriginalSource as DependencyObject)?.DataContext as OmniSweepItem)?.Data is Project target)
        { _viewModel.Reorder(moving, target); e.Handled = true; }
    }
    private void Tile_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (_skipNextTileMouseUp) { _skipNextTileMouseUp = false; e.Handled = true; return; }
        if (_renamingSetup != null) { e.Handled = true; return; }
        if (!_dragged && TileAt(e.OriginalSource as DependencyObject)?.DataContext is OmniSweepItem tile)
        { _viewModel.ExecuteCommand.Execute(tile); e.Handled = true; }
        _dragSetup = null;
    }
    private void Tile_ContextMenuOpening(object sender, ContextMenuEventArgs e)
    {
        var tile = TileAt(e.OriginalSource as DependencyObject);
        var setup = (tile?.DataContext as OmniSweepItem)?.Data as Project;
        if (setup == null) { e.Handled = true; return; }
        ResultsList.SelectedItem = tile!.DataContext;
        var menu = new ContextMenu();
        void Add(string label, Action action) { var item = new MenuItem { Header = label }; item.Click += (_, _) => action(); menu.Items.Add(item); }
        Add("Rename", () => BeginRename(setup, tile));
        Add("Change shortcut", () => WithPrompt("Unused key (WASD reserved; F1–F24 and Shift supported)", setup.Shortcut, value => _viewModel.SetShortcut(setup, value)));
        Add("Re-snapshot (keep commands)", () => _viewModel.RequestSnapshot(setup));
        Add("Replace snapshot", () => _viewModel.RequestSnapshot(setup, true));
        Add("Edit in Hub settings / Setups", () => _viewModel.EditSetup(setup));
        _interaction = true;
        menu.Closed += (_, _) => EndInteraction();
        tile.ContextMenu = menu; menu.PlacementTarget = tile; menu.IsOpen = true; e.Handled = true;
    }
    private static FrameworkElement? FindNamedDescendant(DependencyObject root, string name)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is FrameworkElement element && element.Name == name) return element;
            var found = FindNamedDescendant(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private void BeginRename(Project setup, ListBoxItem tile)
    {
        if (_renameEditor != null) FinishRename(save: false, restoreGridFocus: false);
        _renamingSetup = setup;
        _interaction = true;
        // Let the context menu close before transferring focus to the inline editor.
        Dispatcher.BeginInvoke(new Action(() =>
        {
            if (_closing || _closed) { _renamingSetup = null; return; }
            var editor = FindNamedDescendant(tile, "TileNameEditor") as TextBox;
            var label = FindNamedDescendant(tile, "TileNameLabel");
            if (editor == null || label == null) { _renamingSetup = null; EndInteraction(); return; }
            _renameEditor = editor;
            editor.Text = setup.Name;
            editor.ToolTip = null;
            editor.BorderBrush = new SolidColorBrush(Color.FromRgb(103, 184, 255));
            label.Visibility = Visibility.Collapsed;
            editor.Visibility = Visibility.Visible;
            editor.Focus();
            editor.SelectAll();
        }), System.Windows.Threading.DispatcherPriority.ContextIdle);
    }

    private void FinishRename(bool save, bool restoreGridFocus)
    {
        var editor = _renameEditor;
        var setup = _renamingSetup;
        if (editor == null || setup == null) return;
        if (save && string.IsNullOrWhiteSpace(editor.Text))
        {
            if (IsActive && editor.IsKeyboardFocusWithin)
            {
                editor.ToolTip = "Name cannot be empty";
                editor.BorderBrush = new SolidColorBrush(Color.FromRgb(255, 125, 125));
                editor.SelectAll();
                return;
            }
            save = false;
        }
        var name = editor.Text;
        _renameEditor = null;
        _renamingSetup = null;
        editor.Visibility = Visibility.Collapsed;
        if (editor.Parent is DependencyObject parent && FindNamedDescendant(parent, "TileNameLabel") is { } label)
            label.Visibility = Visibility.Visible;
        if (save) _viewModel.Rename(setup, name);
        EndInteraction();
        if (restoreGridFocus && !_closing && !_closed && IsActive) FocusGrid();
    }

    private void TileNameEditor_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) { FinishRename(save: true, restoreGridFocus: true); e.Handled = true; }
        else if (e.Key == Key.Escape) { FinishRename(save: false, restoreGridFocus: true); e.Handled = true; }
    }

    private void TileNameEditor_LostKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        if (ReferenceEquals(sender, _renameEditor)) FinishRename(save: true, restoreGridFocus: false);
    }

    private void WithPrompt(string title, string value, Func<string, bool> save)
    {
        _interaction = true;
        try
        {
            var result = SetupTextPrompt.Show(this, title, value);
            if (result != null && !save(result)) MessageBox.Show(this, "Use an unused shortcut or a non-empty name.", title);
        }
        finally
        {
            if (!_closing && !_closed) { Activate(); FocusGrid(); }
            EndInteraction();
        }
    }
}

internal static class SetupTextPrompt
{
    public static string? Show(Window owner, string title, string value)
    {
        var input = new TextBox { Text = value, Margin = new Thickness(15), MinWidth = 320, Padding = new Thickness(6) };
        var dialog = new Window { Owner = owner, Title = title, SizeToContent = SizeToContent.WidthAndHeight,
            WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize, Background = new SolidColorBrush(Color.FromRgb(32, 37, 47)) };
        var panel = new StackPanel(); panel.Children.Add(input);
        var ok = new Button { Content = "Save", IsDefault = true, Margin = new Thickness(15, 0, 15, 15), Padding = new Thickness(8) };
        ok.Click += (_, _) => dialog.DialogResult = true; panel.Children.Add(ok); dialog.Content = panel;
        dialog.Loaded += (_, _) => { input.Focus(); input.SelectAll(); };
        dialog.PreviewKeyDown += (_, e) => { if (e.Key == Key.Escape) { dialog.DialogResult = false; e.Handled = true; } };
        return dialog.ShowDialog() == true ? input.Text : null;
    }
}
