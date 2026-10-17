using System.IO;

namespace OmniSync.Hub.Logic.Services;

/// <summary>
/// Reversible calendar/reminder background ownership switch for the parallel Rust Hub.
/// This is intentionally scoped to calendar ingestion and PC reminder workers, not
/// unrelated hosted services or the old Hub's HTTP/SignalR transport.
/// The flag must be activated only after the Rust import preflight is ready.
/// </summary>
internal static class CalendarReminderOwnership
{
    internal static string MarkerPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "OmniHub", "calendar-reminders-rust.flag");

    internal static bool RustOwnsBackgroundWork => File.Exists(MarkerPath);
}
