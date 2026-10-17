using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Globalization;
using System.Text;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Services.AssistantCommands;

public sealed partial class StremioMovieCommand : IAssistantCommand
{
    private static readonly ConcurrentDictionary<string, (DateTimeOffset At, IReadOnlyList<MovieCandidate> Candidates)> SearchCache =
        new(StringComparer.OrdinalIgnoreCase);

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
        2,
        "Resolve a movie title and play it in Stremio.",
        AssistantExecutionHost.Desktop,
        new[]
        {
            new AssistantArgumentSpec("title", AssistantArgumentType.String, true, "Movie title, optionally with a year.")
        },
        new[]
        {
            new AssistantCommandExample(
                "play the Tetris movie",
                new Dictionary<string, string> { ["title"] = "Tetris" }),
            new AssistantCommandExample(
                "play movie Dune 2021",
                new Dictionary<string, string> { ["title"] = "Dune 2021" })
        },
        AssistantTimingSupport.RelativeTime,
        new AssistantStalenessPolicy(TimeSpan.FromMinutes(10)),
        new AssistantRiskPolicy(AssistantRiskLevel.Low),
        new AssistantExecutionPolicy(
            TimeSpan.FromSeconds(120),
            "stremio",
            Idempotent: false,
            new[] { @"Automation\play_stremio_movie.ps1" }));

    [GeneratedRegex(
        @"^(?:please\s+)?(?:play|watch|open)\s+(?:the\s+)?(?<title>.+?)\s+(?:movie|film)\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex TitleThenMoviePattern();

    [GeneratedRegex(
        @"^(?:please\s+)?(?:play|watch|open)\s+(?:movie|film)\s+(?<title>.+?)\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex MovieThenTitlePattern();

    [GeneratedRegex(@"^tt\d{5,12}$", RegexOptions.CultureInvariant)]
    private static partial Regex ImdbIdPattern();

    [GeneratedRegex(@"^(?<title>.+?)\s+(?<year>(?:19|20)\d{2})$", RegexOptions.CultureInvariant)]
    private static partial Regex TrailingYearPattern();

    public AssistantParseResult? TryParse(AssistantParseInput input)
    {
        var text = input.Text.Trim();
        var match = TitleThenMoviePattern().Match(text);
        if (!match.Success)
            match = MovieThenTitlePattern().Match(text);
        if (!match.Success)
            return null;

        var title = match.Groups["title"].Value.Trim().Trim('"', '\'');
        if (!IsUsefulTitle(title))
            return null;

        return new AssistantParseResult(
            new Dictionary<string, JsonElement>(StringComparer.OrdinalIgnoreCase)
            {
                ["title"] = AssistantJson.String(title)
            },
            600 + title.Length);
    }

    public async Task<AssistantCommandOutcome> ExecuteAsync(
        AssistantCommandInvocation invocation,
        CancellationToken cancellationToken)
    {
        var arguments = invocation.Run.Arguments
            ?? throw new InvalidOperationException("Movie arguments are missing.");
        var title = AssistantJson.GetString(arguments, "title");
        var candidates = await ResolveCandidatesAsync(title, cancellationToken);
        var selection = SelectMovie(title, candidates);

        if (selection.Candidate is null)
        {
            return new AssistantCommandOutcome.NeedsClarification(
                $"I could not confidently match '{title}'. Which movie did you mean?",
                selection.Choices
                    .Select(candidate => new AssistantChoice(
                        DisplayName(candidate),
                        new Dictionary<string, JsonElement>
                        {
                            ["movieId"] = AssistantJson.String(candidate.Id)
                        }))
                    .ToList());
        }

        var movie = selection.Candidate;
        if (!ImdbIdPattern().IsMatch(movie.Id))
        {
            return new AssistantCommandOutcome.Failed(
                $"Stremio returned an invalid movie id for {DisplayName(movie)}.");
        }

        var scriptPath = Path.Combine(
            AppContext.BaseDirectory,
            "Automation",
            "play_stremio_movie.ps1");
        if (!File.Exists(scriptPath))
            return new AssistantCommandOutcome.Failed(
                "Stremio playback automation script was not found.");

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
        startInfo.ArgumentList.Add("-ExpectedTitle");
        startInfo.ArgumentList.Add(movie.Name);
        startInfo.ArgumentList.Add("-Quality");
        startInfo.ArgumentList.Add("1080p,720p,2160p");
        startInfo.ArgumentList.Add("-TimeoutSeconds");
        startInfo.ArgumentList.Add("105");

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Could not start Stremio playback automation.");

        try
        {
            var stdoutTask = process.StandardOutput.ReadToEndAsync(cancellationToken);
            var stderrTask = process.StandardError.ReadToEndAsync(cancellationToken);
            await process.WaitForExitAsync(cancellationToken);
            var stdout = await stdoutTask;
            var stderr = await stderrTask;

            if (process.ExitCode != 0)
            {
                var detail = string.IsNullOrWhiteSpace(stderr)
                    ? stdout.Trim()
                    : stderr.Trim();
                return process.ExitCode switch
                {
                    2 => new AssistantCommandOutcome.Failed(
                        $"No suitable Stremio source was found: {detail}",
                        Retryable: false),
                    3 => new AssistantCommandOutcome.Failed(
                        $"Stremio window automation failed: {detail}",
                        Retryable: true),
                    _ => new AssistantCommandOutcome.Failed(
                        $"Stremio playback failed: {detail}",
                        Retryable: true)
                };
            }

            JsonElement? evidence = ParseEvidence(stdout);
            _logger.LogInformation(
                "[Assistant] Played movie {Movie} ({MovieId}). {Output}",
                DisplayName(movie),
                movie.Id,
                stdout.Trim());

            var prefix = selection.IsClosestMatch ? "Playing closest match" : "Playing";
            return new AssistantCommandOutcome.Succeeded(
                $"{prefix} {DisplayName(movie)} in Stremio.",
                evidence);
        }
        catch (OperationCanceledException)
        {
            try
            {
                if (!process.HasExited)
                    process.Kill(entireProcessTree: true);
            }
            catch (Exception killError)
            {
                _logger.LogWarning(killError, "[Assistant] Could not terminate cancelled Stremio automation.");
            }
            throw;
        }
    }

    private async Task<IReadOnlyList<MovieCandidate>> ResolveCandidatesAsync(
        string spokenTitle,
        CancellationToken cancellationToken)
    {
        var (queryTitle, _) = SplitYearHint(spokenTitle);
        var cacheKey = Normalize(queryTitle);
        if (SearchCache.TryGetValue(cacheKey, out var cached) &&
            DateTimeOffset.UtcNow - cached.At < TimeSpan.FromMinutes(1))
        {
            return cached.Candidates;
        }

        var client = _httpClientFactory.CreateClient();
        var url =
            $"https://v3-cinemeta.strem.io/catalog/movie/top/search={Uri.EscapeDataString(queryTitle)}.json";

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(8));

        await using var stream = await client.GetStreamAsync(url, timeout.Token);
        using var document = await JsonDocument.ParseAsync(
            stream,
            cancellationToken: timeout.Token);

        if (!document.RootElement.TryGetProperty("metas", out var metas) ||
            metas.ValueKind != JsonValueKind.Array)
            return Array.Empty<MovieCandidate>();

        var candidates = new List<MovieCandidate>();
        var rank = 0;
        foreach (var element in metas.EnumerateArray())
        {
            rank++;
            if (!element.TryGetProperty("id", out var idNode) ||
                !element.TryGetProperty("name", out var nameNode))
                continue;

            var id = idNode.GetString() ?? string.Empty;
            var name = nameNode.GetString() ?? string.Empty;
            if (id.Length == 0 || name.Length == 0)
                continue;

            string? year = null;
            if (element.TryGetProperty("year", out var yearNode))
                year = yearNode.ToString();
            else if (element.TryGetProperty("releaseInfo", out var releaseNode))
                year = ExtractYear(releaseNode.GetString());

            candidates.Add(new MovieCandidate(id, name, year, rank));
        }

        SearchCache[cacheKey] = (DateTimeOffset.UtcNow, candidates);
        return candidates;
    }

    public static MovieSelection SelectMovie(
        string spokenTitle,
        IReadOnlyList<MovieCandidate> candidates)
    {
        if (candidates.Count == 0)
            return new MovieSelection(null, false, Array.Empty<MovieCandidate>());

        var (title, yearHint) = SplitYearHint(spokenTitle);
        var normalizedTitle = Normalize(title);

        var exact = candidates
            .Where(candidate => Normalize(candidate.Name) == normalizedTitle)
            .ToList();
        if (exact.Count > 0)
        {
            var selected = yearHint is null
                ? exact.OrderBy(candidate => candidate.Rank).First()
                : exact.FirstOrDefault(candidate => candidate.Year == yearHint)
                    ?? exact.OrderBy(candidate => candidate.Rank).First();
            return new MovieSelection(selected, false, exact);
        }

        var ranked = candidates
            .Select(candidate => new
            {
                Candidate = candidate,
                Score = Similarity(normalizedTitle, Normalize(candidate.Name))
            })
            .OrderByDescending(item => item.Score)
            .ThenBy(item => item.Candidate.Rank)
            .ToList();

        if (ranked[0].Score >= 0.85)
            return new MovieSelection(ranked[0].Candidate, true, ranked.Select(x => x.Candidate).Take(3).ToList());

        return new MovieSelection(
            null,
            false,
            ranked.Select(item => item.Candidate).Take(3).ToList());
    }

    private static bool IsUsefulTitle(string title)
    {
        var normalized = Normalize(title);
        if (normalized.Length < 2)
            return false;

        return normalized is not ("the" or "a" or "an" or "movie" or "film" or "out" or "out for");
    }

    private static (string Title, string? Year) SplitYearHint(string spokenTitle)
    {
        var trimmed = spokenTitle.Trim();
        var match = TrailingYearPattern().Match(trimmed);
        return match.Success
            ? (match.Groups["title"].Value.Trim(), match.Groups["year"].Value)
            : (trimmed, null);
    }

    private static string? ExtractYear(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;
        var match = Regex.Match(value, @"(?:19|20)\d{2}");
        return match.Success ? match.Value : null;
    }

    private static string Normalize(string value)
    {
        var decomposed = value.Normalize(NormalizationForm.FormD);
        var builder = new StringBuilder();
        foreach (var ch in decomposed)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) == UnicodeCategory.NonSpacingMark)
                continue;
            builder.Append(char.IsLetterOrDigit(ch) ? char.ToLowerInvariant(ch) : ' ');
        }

        var collapsed = string.Join(
            " ",
            builder.ToString().Split(
                ' ',
                StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));

        foreach (var article in new[] { "the ", "a ", "an " })
        {
            if (collapsed.StartsWith(article, StringComparison.Ordinal))
                return collapsed[article.Length..];
        }

        return collapsed;
    }

    private static double Similarity(string left, string right)
    {
        if (left == right)
            return 1;
        if (left.Length == 0 || right.Length == 0)
            return 0;

        var leftTokens = left.Split(' ', StringSplitOptions.RemoveEmptyEntries).ToHashSet();
        var rightTokens = right.Split(' ', StringSplitOptions.RemoveEmptyEntries).ToHashSet();
        var intersection = leftTokens.Intersect(rightTokens).Count();
        var tokenScore = (2.0 * intersection) / (leftTokens.Count + rightTokens.Count);

        var distance = Levenshtein(left, right);
        var characterScore = 1.0 - (double)distance / Math.Max(left.Length, right.Length);
        return Math.Max(tokenScore, characterScore);
    }

    private static int Levenshtein(string left, string right)
    {
        var previous = Enumerable.Range(0, right.Length + 1).ToArray();
        for (var i = 1; i <= left.Length; i++)
        {
            var current = new int[right.Length + 1];
            current[0] = i;
            for (var j = 1; j <= right.Length; j++)
            {
                var cost = left[i - 1] == right[j - 1] ? 0 : 1;
                current[j] = Math.Min(
                    Math.Min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[^1];
    }

    private static JsonElement? ParseEvidence(string stdout)
    {
        var lines = stdout
            .Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)
            .Reverse();
        foreach (var line in lines)
        {
            var trimmed = line.Trim();
            if (trimmed.StartsWith("RESULT ", StringComparison.Ordinal))
                trimmed = trimmed["RESULT ".Length..].Trim();
            if (!trimmed.StartsWith('{') || !trimmed.EndsWith('}'))
                continue;
            try
            {
                using var document = JsonDocument.Parse(trimmed);
                return document.RootElement.Clone();
            }
            catch (JsonException)
            {
            }
        }
        return null;
    }

    private static string DisplayName(MovieCandidate candidate)
        => string.IsNullOrWhiteSpace(candidate.Year)
            ? candidate.Name
            : $"{candidate.Name} ({candidate.Year})";
}

public sealed record MovieCandidate(
    string Id,
    string Name,
    string? Year,
    int Rank);

public sealed record MovieSelection(
    MovieCandidate? Candidate,
    bool IsClosestMatch,
    IReadOnlyList<MovieCandidate> Choices);
