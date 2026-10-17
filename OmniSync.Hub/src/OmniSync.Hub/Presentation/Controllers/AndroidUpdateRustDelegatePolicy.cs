using System.Net;
using System.IO;

namespace OmniSync.Hub.Presentation.Controllers;

/// <summary>
/// An opt-in, local-only Android REST bridge during dual-Hub validation.
/// Keeping the upstream pinned to the Rust parallel port prevents config
/// mistakes from creating an open HTTP proxy or forwarding back to C#.
/// </summary>
public static class AndroidUpdateRustDelegatePolicy
{
    public static string OwnershipFlagPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "OmniHub", "android-rust.flag");

    public static Uri? ResolveLoopbackBaseUrl(string? configured)
    {
        var value = string.IsNullOrWhiteSpace(configured)
            ? "http://127.0.0.1:5001/"
            : configured.Trim();
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttp
            || uri.Port != 5001
            || !IPAddress.TryParse(uri.Host, out var ip)
            || !IPAddress.IsLoopback(ip)
            || !string.IsNullOrEmpty(uri.UserInfo)
            || uri.AbsolutePath != "/"
            || !string.IsNullOrEmpty(uri.Query)
            || !string.IsNullOrEmpty(uri.Fragment))
        {
            return null;
        }

        return uri;
    }
}
