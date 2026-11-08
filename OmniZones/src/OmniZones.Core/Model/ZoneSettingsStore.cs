namespace OmniZones.Model;

/// <summary>Loads and saves <see cref="ZoneSettings"/> as JSON. Default location: %APPDATA%\OmniZones\settings.json.</summary>
public sealed class ZoneSettingsStore
{
    private readonly object _gate = new();
    private ZoneSettings _current;
    private DateTime _lastWriteUtc;
    private long _lastLength;

    public string Path { get; }
    public ZoneSettings Current { get { lock (_gate) return _current; } }
    /// <summary>Raised (on the saving thread) after <see cref="Save"/> replaced <see cref="Current"/>.</summary>
    public event Action? Changed;

    /// <summary>%APPDATA%\OmniZones\settings.json, or the file named by the OMNIZONES_SETTINGS environment variable (used by tests).</summary>
    public static string DefaultPath =>
        Environment.GetEnvironmentVariable("OMNIZONES_SETTINGS") is { Length: > 0 } overridePath
            ? overridePath
            : System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniZones", "settings.json");

    public ZoneSettingsStore(string? path = null)
    {
        Path = path ?? DefaultPath;
        _current = Load();
        var info = new FileInfo(Path);
        if (info.Exists) { _lastWriteUtc = info.LastWriteTimeUtc; _lastLength = info.Length; }
    }

    /// <summary>Observe another editor's atomic writes without restarting the standalone runtime.</summary>
    public bool ReloadIfChanged()
    {
        ZoneSettings? updated;
        try
        {
            var info = new FileInfo(Path);
            if (!info.Exists) return false;
            lock (_gate)
            {
                if (info.LastWriteTimeUtc == _lastWriteUtc && info.Length == _lastLength) return false;
                updated = ZoneJson.Deserialize<ZoneSettings>(File.ReadAllText(Path));
                if (updated?.Layouts == null || updated.Layouts.Count == 0) return false;
                if (updated.Layouts.All(l => l.Id != updated.ActiveLayoutId))
                    updated.ActiveLayoutId = updated.Layouts[0].Id;
                _current = updated;
                _lastWriteUtc = info.LastWriteTimeUtc;
                _lastLength = info.Length;
            }
        }
        catch { return false; } // An incomplete/invalid external write is never adopted.
        Changed?.Invoke();
        return true;
    }

    private ZoneSettings Load()
    {
        ZoneSettings? s = null;
        try
        {
            if (File.Exists(Path)) s = ZoneJson.Deserialize<ZoneSettings>(File.ReadAllText(Path));
        }
        catch
        {
            try { File.Move(Path, Path + ".broken", true); } catch { /* keep going with defaults */ }
        }
        s ??= new ZoneSettings();
        if (s.Layouts.Count == 0) s.Layouts = DefaultLayouts.Create();
        if (s.Layouts.All(l => l.Id != s.ActiveLayoutId)) s.ActiveLayoutId = s.Layouts[0].Id;
        if (s.Version < 2)
        {
            // v2: the default gap became 0 px. Layouts still on an old default gap (12 or 8) follow; deliberate values stay.
            foreach (var l in s.Layouts.Where(l => l.Gap is 12 or 8)) l.Gap = 0;
            s.Version = 2;
        }
        return s;
    }

    /// <summary>Persist <paramref name="settings"/> and make it the current snapshot.</summary>
    public void Save(ZoneSettings settings)
    {
        settings.Version = Math.Max(settings.Version, 2);
        if (settings.Layouts.Count == 0) settings.Layouts = DefaultLayouts.Create();
        if (settings.Layouts.All(l => l.Id != settings.ActiveLayoutId)) settings.ActiveLayoutId = settings.Layouts[0].Id;
        lock (_gate)
        {
            _current = settings;
            try
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
                var tmp = Path + ".tmp";
                File.WriteAllText(tmp, ZoneJson.Serialize(settings));
                File.Move(tmp, Path, true);
                var info = new FileInfo(Path);
                _lastWriteUtc = info.LastWriteTimeUtc;
                _lastLength = info.Length;
            }
            catch { /* settings stay in memory; next save retries */ }
        }
        Changed?.Invoke();
    }

    /// <summary>Edit a copy and save it.</summary>
    public void Update(Action<ZoneSettings> edit)
    {
        ReloadIfChanged();
        var copy = Current.Clone();
        edit(copy);
        Save(copy);
    }
}
