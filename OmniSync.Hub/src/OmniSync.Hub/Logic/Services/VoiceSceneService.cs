using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Models;

namespace OmniSync.Hub.Logic.Services;

/// <summary>
/// Fixed, parameterless actions exposed through Voice-category Google Home macros.
/// Scenes are not free-text commands; Google sends only the chosen scene ID.
/// </summary>
public interface IVoiceSceneService
{
    Task ExecuteAsync(string name);
}

public sealed class VoiceSceneService : IVoiceSceneService
{
    private const int WmSysCommand = 0x0112;
    private const int ScMonitorPower = 0xF170;
    private static readonly IntPtr HwndBroadcast = new(0xFFFF);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool LockWorkStation();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SendMessageTimeout(
        IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam,
        uint flags, uint timeout, out IntPtr result);

    private readonly CalendarService _calendar;
    private readonly IReminderRepository _reminders;
    private readonly ProcessService _process;
    private readonly InputService _input;
    private readonly HubSettingsService _settings;
    private readonly HubMonitorService _monitor;
    private int _dictationActive;

    public VoiceSceneService(
        CalendarService calendar,
        IReminderRepository reminders,
        ProcessService process,
        InputService input,
        HubSettingsService settings,
        HubMonitorService monitor)
    {
        _calendar = calendar;
        _reminders = reminders;
        _process = process;
        _input = input;
        _settings = settings;
        _monitor = monitor;
    }

    public async Task ExecuteAsync(string name)
    {
        switch (name.Trim().ToLowerInvariant())
        {
            case "morning":
                await MorningAsync();
                break;
            case "idle":
                await IdleAsync();
                break;
            case "music":
                await MusicAsync();
                break;
            case "pause":
                await PauseAsync();
                break;
            case "dictate":
                await DictateAsync();
                break;
            default:
                throw new ArgumentException($"Unknown built-in voice scene: {name}");
        }
    }

    private async Task MorningAsync()
    {
        var now = DateTime.Now;
        var events = _calendar.GetEventsSnapshot()
            .Where(e => e.Start.Date == now.Date || (e.End > now && e.Start < now.Date.AddDays(1)))
            .OrderBy(e => e.Start)
            .Take(4)
            .ToList();

        var reminders = (await _reminders.GetAllAsync())
            .Where(r => r.Source == ReminderSource.Local && !r.IsTerminal &&
                        r.DueAt.LocalDateTime < now.Date.AddDays(1))
            .OrderBy(r => r.DueAt)
            .Take(3)
            .ToList();

        var briefing = new StringBuilder($"Good morning. It is {now:dddd, MMMM d}, {now:h:mm tt}. ");
        if (events.Count == 0)
        {
            briefing.Append("There are no calendar events listed for today.");
        }
        else
        {
            briefing.Append($"You have {events.Count} calendar ");
            briefing.Append(events.Count == 1 ? "event. " : "events coming up. ");
            foreach (var item in events)
            {
                briefing.Append(item.IsAllDay ? "All day: " : $"At {item.Start:h:mm tt}: ");
                briefing.Append(item.Summary).Append(". ");
            }
        }

        if (reminders.Count > 0)
        {
            briefing.Append($" You have {reminders.Count} outstanding local reminders: ");
            foreach (var reminder in reminders) briefing.Append(reminder.Title).Append(". ");
        }

        var spoken = await RunScriptAsync("voice_speak.ps1", new[] { "-Text", briefing.ToString() }, 45);
        _monitor.AddLogMessage(spoken.ExitCode == 0
            ? "[VoiceScene] Morning briefing spoken."
            : $"[VoiceScene] Morning briefing failed: {spoken.Error}");
    }

    private async Task IdleAsync()
    {
        if (!LockWorkStation())
        {
            _monitor.AddLogMessage("[VoiceScene] Idle: could not lock Windows; leaving monitors on.");
            return;
        }

        await Task.Delay(350);
        // SC_MONITORPOWER, lParam=2 turns displays off without suspending the machine.
        // SMTO_ABORTIFHUNG prevents an unresponsive window from blocking the Hub.
        SendMessageTimeout(HwndBroadcast, WmSysCommand, new IntPtr(ScMonitorPower),
            new IntPtr(2), 0x0002, 1000, out _);
        _monitor.AddLogMessage("[VoiceScene] Idle: Windows locked; monitor-off requested.");
    }

    private async Task MusicAsync()
    {
        var target = _settings.Settings.MusicSceneLaunchTarget;
        if (string.IsNullOrWhiteSpace(target))
        {
            _monitor.AddLogMessage("[VoiceScene] Music: no configured launch target.");
            return;
        }

        // Music must open a fresh playlist, not resume an unrelated media session.
        // The saved YouTube watch ID is static; resolve the latest upload first.
        var resolved = await RunScriptAsync("voice_playlist_latest.ps1", new[] { "-Target", target }, 14);
        var latestResolved = false;
        if (resolved.ExitCode == 0 && Uri.TryCreate(resolved.Output.Trim(), UriKind.Absolute, out var url)
            && (url.Scheme == Uri.UriSchemeHttps || url.Scheme == Uri.UriSchemeHttp))
        {
            target = url.AbsoluteUri;
            latestResolved = true;
        }
        else
        {
            _monitor.AddLogMessage($"[VoiceScene] Music: latest-upload lookup failed ({resolved.Error}); using configured playlist.");
        }

        // Pause a playing session before opening YouTube. Never issue global
        // "play" after launch: Windows could resume the old video instead.
        var state = await RunScriptAsync("voice_media.ps1", new[] { "-Action", "status" }, 8);
        if (state.ExitCode == 0 && state.Output.Trim().Equals("Playing", StringComparison.OrdinalIgnoreCase))
        {
            await RunScriptAsync("voice_media.ps1", new[] { "-Action", "pause" }, 8);
        }

        _process.ShellExecute(target, "");
        _monitor.AddLogMessage(latestResolved
            ? "[VoiceScene] Music: opened latest upload in the playlist (browser autoplay permissions apply)."
            : "[VoiceScene] Music: opened fallback music target (latest upload unavailable).");
    }
    private async Task PauseAsync()
    {
        var result = await RunScriptAsync("voice_media.ps1", new[] { "-Action", "pause" }, 8);
        _monitor.AddLogMessage(result.ExitCode == 0
            ? $"[VoiceScene] Pause: {result.Output.Trim()}."
            : $"[VoiceScene] Pause: nothing paused ({result.Output.Trim()}).");
    }

    private async Task DictateAsync()
    {
        if (Interlocked.Exchange(ref _dictationActive, 1) != 0)
        {
            _monitor.AddLogMessage("[VoiceScene] Dictate: already listening.");
            return;
        }

        try
        {
            var target = ProcessService.GetForegroundWindow();
            if (target == IntPtr.Zero)
            {
                _monitor.AddLogMessage("[VoiceScene] Dictate: no focused input target.");
                return;
            }

            _monitor.AddLogMessage("[VoiceScene] Dictate: listening on PC microphone (up to 12 seconds).");
            try { System.Media.SystemSounds.Asterisk.Play(); } catch { /* audio cues are optional */ }
            await Task.Delay(300);
            var result = await RunScriptAsync("voice_dictate.ps1", new[] { "-ListenSeconds", "12" }, 19);
            if (result.ExitCode != 0 || string.IsNullOrWhiteSpace(result.Output))
            {
                _monitor.AddLogMessage($"[VoiceScene] Dictate: no transcription ({result.Error}).");
                return;
            }

            // Never inject into a window selected during recognition. The user must
            // deliberately keep the original target focused until dictation ends.
            if (ProcessService.GetForegroundWindow() != target)
            {
                _monitor.AddLogMessage("[VoiceScene] Dictate: focus changed; discarded transcription.");
                return;
            }

            foreach (var character in result.Output.Trim())
            {
                _input.UnicodeDown(character);
                _input.UnicodeUp(character);
            }
            _monitor.AddLogMessage("[VoiceScene] Dictate: text inserted into original foreground window.");
        }
        finally
        {
            Volatile.Write(ref _dictationActive, 0);
        }
    }

    private static async Task<(int ExitCode, string Output, string Error)> RunScriptAsync(
        string file, IReadOnlyList<string> args, int timeoutSeconds)
    {
        var path = Path.Combine(AppContext.BaseDirectory, "Automation", file);
        if (!File.Exists(path))
            return (-1, "", $"Missing script: {file}");

        var start = new ProcessStartInfo("powershell.exe")
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            CreateNoWindow = true
        };
        start.ArgumentList.Add("-NoProfile");
        start.ArgumentList.Add("-NonInteractive");
        start.ArgumentList.Add("-ExecutionPolicy");
        start.ArgumentList.Add("Bypass");
        start.ArgumentList.Add("-File");
        start.ArgumentList.Add(path);
        foreach (var arg in args) start.ArgumentList.Add(arg);

        try
        {
            using var process = Process.Start(start);
            if (process is null) return (-1, "", "Could not launch PowerShell.");
            var outputTask = process.StandardOutput.ReadToEndAsync();
            var errorTask = process.StandardError.ReadToEndAsync();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
            try
            {
                await process.WaitForExitAsync(timeout.Token);
            }
            catch (OperationCanceledException)
            {
                if (!process.HasExited) process.Kill(entireProcessTree: true);
                await process.WaitForExitAsync();
                return (-1, "", "Timed out.");
            }
            return (process.ExitCode, await outputTask, (await errorTask).Trim());
        }
        catch (Exception ex)
        {
            return (-1, "", ex.Message);
        }
    }
}
