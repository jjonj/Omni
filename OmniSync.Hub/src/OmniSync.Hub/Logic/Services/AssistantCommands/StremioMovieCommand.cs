using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed partial class StremioMovieCommand : IAssistantCommand
{
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger<StremioMovieCommand> _logger;

    public StremioMovieCommand(
        IHttpClientFactory httpClientFactory,
        ILogger<StremioMovieCommand> logger)
    {
        _httpClientFactory = httpClientFactory;
        _logger = logger;
    }

    public AssistantCommandDescriptor Descriptor { get; } = new(
        "media.play_movie",
        "Resolve a movie title and play it in Stremio.",
        AssistantExecutionHost.Desktop,
        new Dictionary<string, AssistantArgumentType>
        {
            ["title"] = AssistantArgumentType.String
        },
        AssistantQueuePolicy.SkipBacklog(TimeSpan.FromMinutes(10)));

    [GeneratedRegex(
        @"^(?:please\s+)?(?:play|watch|open)\s+(?:the\s+)?(?<title>.+?)\s+(?:movie|film)\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex TitleThenMoviePattern();

    [GeneratedRegex(
        @"^(?:please\s+)?(?:play|watch|open)\s+(?:movie|film)\s+(?<title>.+?)\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex MovieThenTitlePattern();

    public bool TryParse(
        string text,
        out IReadOnlyDictionary<string, string> arguments)
    {
        var match = TitleThenMoviePattern().Match(text.Trim());
        if (!match.Success)
            match = MovieThenTitlePattern().Match(text.Trim());

        if (!match.Success)
        {
            arguments = new Dictionary<string, string>();
            return false;
        }

        var title = match.Groups["title"].Value.Trim().Trim('"', '\'');
        if (title.Length == 0)
        {
            arguments = new Dictionary<string, string>();
            return false;
        }

        arguments = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["title"] = title
        };
        return true;
    }

    public async Task<AssistantCommandExecutionResult> ExecuteAsync(
        IReadOnlyDictionary<string, string> arguments,
        CancellationToken cancellationToken)
    {
        var title = arguments["title"];
        var movie = await ResolveMovieAsync(title, cancellationToken);
        var scriptPath = Path.Combine(
            AppContext.BaseDirectory,
            "Automation",
            "play_stremio_movie.ps1");

        if (!File.Exists(scriptPath))
            throw new FileNotFoundException(
                "Stremio playback automation script was not found.",
                scriptPath);

        var startInfo = new ProcessStartInfo("powershell.exe")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        startInfo.ArgumentList.Add("-NoProfile");
        startInfo.ArgumentList.Add("-ExecutionPolicy");
        startInfo.ArgumentList.Add("Bypass");
        startInfo.ArgumentList.Add("-File");
        startInfo.ArgumentList.Add(scriptPath);
        startInfo.ArgumentList.Add("-MovieId");
        startInfo.ArgumentList.Add(movie.Id);

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Could not start Stremio playback automation.");
        var stdoutTask = process.StandardOutput.ReadToEndAsync(cancellationToken);
        var stderrTask = process.StandardError.ReadToEndAsync(cancellationToken);
        await process.WaitForExitAsync(cancellationToken);
        var stdout = await stdoutTask;
        var stderr = await stderrTask;

        if (process.ExitCode != 0)
            throw new InvalidOperationException(
                $"Stremio playback failed: {stderr.Trim()}");

        _logger.LogInformation(
            "[Assistant] Played movie {Movie} ({MovieId}). {Output}",
            movie.Name,
            movie.Id,
            stdout.Trim());

        return new AssistantCommandExecutionResult(
            $"Playing {movie.Name} in Stremio.");
    }

    private async Task<(string Id, string Name)> ResolveMovieAsync(
        string title,
        CancellationToken cancellationToken)
    {
        var client = _httpClientFactory.CreateClient();
        var url =
            $"https://v3-cinemeta.strem.io/catalog/movie/top/search={Uri.EscapeDataString(title)}.json";
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(8));

        await using var stream = await client.GetStreamAsync(url, timeout.Token);
        using var document = await JsonDocument.ParseAsync(
            stream,
            cancellationToken: timeout.Token);

        if (!document.RootElement.TryGetProperty("metas", out var metas))
            throw new InvalidOperationException(
                $"Stremio search returned no movies for '{title}'.");

        var candidates = metas.EnumerateArray()
            .Where(element =>
                element.TryGetProperty("id", out _) &&
                element.TryGetProperty("name", out _))
            .Select(element => (
                Id: element.GetProperty("id").GetString() ?? "",
                Name: element.GetProperty("name").GetString() ?? ""))
            .Where(candidate =>
                candidate.Id.Length > 0 &&
                candidate.Name.Length > 0)
            .ToList();

        var selected = candidates.FirstOrDefault(candidate =>
            string.Equals(candidate.Name, title, StringComparison.OrdinalIgnoreCase));
        if (string.IsNullOrWhiteSpace(selected.Id))
            selected = candidates.FirstOrDefault();

        if (string.IsNullOrWhiteSpace(selected.Id))
            throw new InvalidOperationException(
                $"Could not resolve a Stremio movie named '{title}'.");

        return selected;
    }
}
