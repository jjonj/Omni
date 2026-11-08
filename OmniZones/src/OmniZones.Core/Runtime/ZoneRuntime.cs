using System.Windows.Threading;
using OmniZones.Model;
using OmniZones.Native;
using OmniZones.UI;

namespace OmniZones.Runtime;

/// <summary>
/// The whole OmniZones feature as one object: settings, drag watcher, hotkeys and the settings/editor windows.
/// Create it on a thread that has a WPF <see cref="Dispatcher"/> (the standalone app's UI thread), or use
/// <see cref="StartOnDedicatedThread"/> to host it inside another app such as the Omni Hub.
/// </summary>
public sealed class ZoneRuntime : IDisposable
{
    private const int PollMs = 15;
    private const string MutexName = @"Local\OmniZones.Runtime";

    private readonly DispatcherTimer _timer;
    private readonly ZoneKeyboardHook _hook;
    private readonly Mutex _mutex;
    private ZoneSettingsWindow? _settingsWindow;
    private ZoneEditorWindow? _editorWindow;
    private bool _disposed;
    private DateTime _lastSharedSettingsCheck = DateTime.MinValue;

    public ZoneSettingsStore Store { get; }
    public ZoneManager Manager { get; }
    public Dispatcher Dispatcher { get; }

    /// <summary>Activity messages ("Chrome snapped to Middle"...).</summary>
    public event Action<string>? Activity;

    /// <summary>Host hook: when set, the settings window shows a "Start with Windows" toggle that calls these.</summary>
    public Func<bool>? GetAutostart { get; set; }
    public Action<bool>? SetAutostart { get; set; }

    private ZoneRuntime(ZoneSettingsStore store, Mutex mutex)
    {
        Store = store;
        _mutex = mutex;
        Dispatcher = Dispatcher.CurrentDispatcher;
        Manager = new ZoneManager(store);
        Manager.BindDispatcher(Dispatcher);
        Manager.Activity += m => Activity?.Invoke(m);
        _hook = new ZoneKeyboardHook(OnKey);
        _timer = new DispatcherTimer(DispatcherPriority.Normal) { Interval = TimeSpan.FromMilliseconds(PollMs) };
        _timer.Tick += (_, _) => Safe(() =>
        {
            if ((DateTime.UtcNow - _lastSharedSettingsCheck).TotalSeconds >= 1)
            {
                _lastSharedSettingsCheck = DateTime.UtcNow;
                Store.ReloadIfChanged();
            }
            Manager.Tick();
        });
        _timer.Start();
        Dispatcher.BeginInvoke(new Action(() => Safe(Manager.Prewarm)), DispatcherPriority.ApplicationIdle);
    }

    /// <summary>
    /// Start on the calling thread (which must pump a WPF dispatcher). Returns null if another OmniZones
    /// (e.g. the Hub's copy, or the standalone app) is already running in this session.
    /// </summary>
    public static ZoneRuntime? TryStart(ZoneSettingsStore? store = null)
    {
        Mutex mutex;
        try
        {
            mutex = new Mutex(true, MutexName, out var created);
            if (!created) { mutex.Dispose(); return null; }
        }
        catch (UnauthorizedAccessException) { return null; }
        return new ZoneRuntime(store ?? new ZoneSettingsStore(), mutex);
    }

    /// <summary>Host OmniZones on its own STA thread with its own dispatcher (used by the Hub). Null if already running.</summary>
    public static ZoneRuntime? StartOnDedicatedThread(ZoneSettingsStore? store = null)
    {
        ZoneRuntime? runtime = null;
        using var ready = new ManualResetEventSlim();
        var thread = new Thread(() =>
        {
            // The host may not be DPI aware; make this thread see physical pixels like the standalone app does.
            Win32.SetThreadDpiAwarenessContext(Win32.DPI_PER_MONITOR_V2);
            try { runtime = TryStart(store); }
            finally { ready.Set(); }
            if (runtime != null) Dispatcher.Run();
        }) { IsBackground = true, Name = "OmniZones" };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        ready.Wait();
        return runtime;
    }

    private void Safe(Action a)
    {
        try { a(); }
        catch (Exception e) { Activity?.Invoke($"OmniZones error: {e.Message}"); }
    }

    private void Post(Action a) => Dispatcher.BeginInvoke(new Action(() => Safe(a)));

    // ------------------------------------------------------------------ hotkeys

    private bool OnKey(KeyPress k)
    {
        var s = Store.Current;
        if (!s.Enabled || _disposed) return false;
        var vk = k.Vk;

        if (k.Win && !k.Ctrl && !k.Alt && !k.Shift)
        {
            if (s.OverrideWinArrows && vk is Win32.VK_LEFT or Win32.VK_RIGHT or Win32.VK_UP or Win32.VK_DOWN && Manager.CanAct)
            {
                // Win+Down on a window we do not manage keeps its normal meaning (minimize).
                if (vk == Win32.VK_DOWN && !Manager.IsManaged(WindowTools.Foreground())) return false;
                Post(() => Manager.WinArrow(vk));
                return true;
            }
            if (vk == Win32.VK_OEM_3 && !s.SimpleMode) { Post(Manager.TogglePeek); return true; }
        }
        if (k.Win && k.Ctrl && !k.Alt && !k.Shift && vk == 'Z') { Post(Manager.Undo); return true; }
        if (k.Win && k.Alt && !k.Ctrl && !s.SimpleMode)
        {
            if (vk == Win32.VK_DOWN) { Post(() => Manager.CycleStack(1)); return true; }
            if (vk == Win32.VK_UP) { Post(() => Manager.CycleStack(-1)); return true; }
            if (vk == 'T') { Post(Manager.Gather); return true; }
            var ch = vk is >= '0' and <= '9' or >= 'A' and <= 'Z' ? (char)vk : '\0';
            if (ch != '\0' && s.Layouts.Any(l => l.Key.Length == 1 && char.ToUpperInvariant(l.Key[0]) == ch))
            {
                Post(() => Manager.ApplyLayoutByKey(ch));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ windows

    public void OpenSettings() => Dispatcher.BeginInvoke(new Action(() => Safe(() =>
    {
        if (_settingsWindow == null)
        {
            _settingsWindow = new ZoneSettingsWindow(this);
            _settingsWindow.Closed += (_, _) => _settingsWindow = null;
        }
        Present(_settingsWindow);
    })));

    public void OpenEditor() => Dispatcher.BeginInvoke(new Action(() => Safe(() =>
    {
        // The editor edits exactly one profile: the active one at the time it was opened.
        if (_editorWindow != null && _editorWindow.LayoutId != Store.Current.ActiveLayoutId) _editorWindow.Close();
        if (_editorWindow == null)
        {
            _editorWindow = new ZoneEditorWindow(this);
            _editorWindow.Closed += (_, _) => _editorWindow = null;
        }
        Present(_editorWindow);
    })));

    private static void Present(System.Windows.Window w)
    {
        if (!w.IsVisible) w.Show();
        if (w.WindowState == System.Windows.WindowState.Minimized) w.WindowState = System.Windows.WindowState.Normal;
        w.Activate();
    }

    /// <summary>Flip a boolean setting from the host (tray menu).</summary>
    public void Toggle(Action<ZoneSettings> edit) => Store.Update(edit);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        void Teardown()
        {
            _timer.Stop();
            _hook.Dispose();
            _settingsWindow?.Close();
            _editorWindow?.Close();
            Manager.Shutdown();
            try { _mutex.ReleaseMutex(); } catch { /* released by another thread: fine */ }
            _mutex.Dispose();
        }
        if (Dispatcher.CheckAccess()) Teardown(); else Dispatcher.Invoke(Teardown);
    }

    /// <summary>Stop a runtime started with <see cref="StartOnDedicatedThread"/> and end its thread.</summary>
    public void Stop()
    {
        Dispose();
        Dispatcher.InvokeShutdown();
    }
}
