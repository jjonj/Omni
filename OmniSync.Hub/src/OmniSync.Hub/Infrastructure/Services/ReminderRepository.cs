using System.IO;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Models;

namespace OmniSync.Hub.Infrastructure.Services;

public interface IReminderRepository
{
    Task<IReadOnlyList<ReminderItem>> GetAllAsync(CancellationToken cancellationToken = default);

    Task<ReminderItem?> FindBySourceIdentityAsync(
        string sourceIdentity,
        CancellationToken cancellationToken = default);

    Task<ReminderItem> UpsertMirroredAsync(
        ReminderItem incoming,
        CancellationToken cancellationToken = default);

    Task<ReminderItem?> UpdateAsync(
        Guid id,
        Func<ReminderItem, ReminderItem> update,
        CancellationToken cancellationToken = default);
}

public sealed class ReminderRepository : IReminderRepository
{
    private const int CurrentSchemaVersion = 1;
    private readonly string _path;
    private readonly ILogger<ReminderRepository>? _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private ReminderStoreDocument? _document;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    public ReminderRepository(ILogger<ReminderRepository>? logger = null, string? path = null)
    {
        _logger = logger;
        _path = path ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "OmniSync",
            "reminders-v1.json");
    }

    public async Task<IReadOnlyList<ReminderItem>> GetAllAsync(
        CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken);
        try
        {
            await EnsureLoadedAsync(cancellationToken);
            return _document!.Items.Select(x => x.Copy()).ToList();
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<ReminderItem?> FindBySourceIdentityAsync(
        string sourceIdentity,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourceIdentity);
        await _gate.WaitAsync(cancellationToken);
        try
        {
            await EnsureLoadedAsync(cancellationToken);
            return _document!.Items
                .FirstOrDefault(x => x.GetSourceIdentity() == sourceIdentity)
                ?.Copy();
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<ReminderItem> UpsertMirroredAsync(
        ReminderItem incoming,
        CancellationToken cancellationToken = default)
    {
        var sourceIdentity = incoming.GetSourceIdentity()
            ?? throw new ArgumentException(
                "Mirrored reminders require a stable source identity.",
                nameof(incoming));

        await _gate.WaitAsync(cancellationToken);

        try
        {
            await EnsureLoadedAsync(cancellationToken);
            var existing = _document!.Items
                .FirstOrDefault(x => x.GetSourceIdentity() == sourceIdentity);

            if (existing is null)
            {
                var created = incoming.Copy();
                created.Id = created.Id == Guid.Empty ? Guid.NewGuid() : created.Id;
                created.Ownership = ReminderOwnership.Mirroring;
                created.Revision = Math.Max(1, created.Revision);
                created.CreatedAt = created.CreatedAt == default
                    ? DateTimeOffset.UtcNow
                    : created.CreatedAt;
                created.UpdatedAt = DateTimeOffset.UtcNow;
                _document.Items.Add(created);
                await PersistAsync(cancellationToken);
                return created.Copy();
            }

            // Calendar stops being authoritative for an occurrence after it is claimed.
            if (existing.Ownership == ReminderOwnership.Owned)
                return existing.Copy();

            if (!ApplyMirroredFields(existing, incoming))
                return existing.Copy();

            existing.Revision++;
            existing.UpdatedAt = DateTimeOffset.UtcNow;
            await PersistAsync(cancellationToken);
            return existing.Copy();

        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<ReminderItem?> UpdateAsync(
        Guid id,
        Func<ReminderItem, ReminderItem> update,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(update);
        await _gate.WaitAsync(cancellationToken);
        try
        {
            await EnsureLoadedAsync(cancellationToken);
            var index = _document!.Items.FindIndex(x => x.Id == id);
            if (index < 0)
                return null;

            var existing = _document.Items[index];
            var updated = update(existing.Copy())
                ?? throw new InvalidOperationException("Reminder update returned null.");

            updated.Id = existing.Id;
            updated.CreatedAt = existing.CreatedAt;
            updated.Revision = existing.Revision + 1;
            updated.UpdatedAt = DateTimeOffset.UtcNow;
            _document.Items[index] = updated;
            await PersistAsync(cancellationToken);
            return updated.Copy();
        }
        finally
        {
            _gate.Release();
        }
    }

    private static bool ApplyMirroredFields(ReminderItem target, ReminderItem source)
    {
        var changed =
            target.Title != source.Title ||
            target.Kind != source.Kind ||
            target.DueAt != source.DueAt ||
            target.NextFireAt != source.NextFireAt ||
            target.SourceVersion != source.SourceVersion ||
            target.SourceOriginalTitle != source.SourceOriginalTitle ||
            target.RecurrenceSeriesId != source.RecurrenceSeriesId ||
            target.RecurrenceRule != source.RecurrenceRule ||
            target.Notes != source.Notes ||
            target.IsAllDay != source.IsAllDay ||
            (target.State == ReminderState.Cancelled && source.State == ReminderState.Pending);

        if (!changed)
            return false;

        target.Title = source.Title;
        target.Kind = source.Kind;
        target.DueAt = source.DueAt;
        target.NextFireAt = source.NextFireAt;
        target.SourceVersion = source.SourceVersion;
        target.SourceOriginalTitle = source.SourceOriginalTitle;
        target.RecurrenceSeriesId = source.RecurrenceSeriesId;
        target.RecurrenceRule = source.RecurrenceRule;
        target.Notes = source.Notes;
        target.IsAllDay = source.IsAllDay;

        if (target.State == ReminderState.Cancelled && source.State == ReminderState.Pending)
            target.State = ReminderState.Pending;

        return true;
    }

    private async Task EnsureLoadedAsync(CancellationToken cancellationToken)
    {
        if (_document is not null)
            return;

        if (!File.Exists(_path))
        {
            _document = new ReminderStoreDocument();
            return;
        }

        await using var stream = File.OpenRead(_path);
        _document = await JsonSerializer.DeserializeAsync<ReminderStoreDocument>(
            stream,
            JsonOptions,
            cancellationToken) ?? throw new InvalidDataException(
                $"Reminder store is empty or invalid: {_path}");

        if (_document.SchemaVersion != CurrentSchemaVersion)
        {
            throw new InvalidDataException(
                $"Unsupported reminder store schema {_document.SchemaVersion}; " +
                $"expected {CurrentSchemaVersion}.");
        }
    }

    private async Task PersistAsync(CancellationToken cancellationToken)
    {
        var directory = Path.GetDirectoryName(_path)
            ?? throw new InvalidOperationException(
                "Reminder store path has no parent directory.");

        Directory.CreateDirectory(directory);
        var tempPath = _path + ".tmp";

        await using (var stream = new FileStream(
            tempPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            4096,
            useAsync: true))
        {
            await JsonSerializer.SerializeAsync(
                stream,
                _document,
                JsonOptions,
                cancellationToken);
            await stream.FlushAsync(cancellationToken);
        }

        File.Move(tempPath, _path, overwrite: true);
        _logger?.LogDebug(
            "Persisted {Count} reminders to {Path}.",
            _document!.Items.Count,
            _path);
    }

    private sealed class ReminderStoreDocument
    {
        public int SchemaVersion { get; set; } = CurrentSchemaVersion;
        public List<ReminderItem> Items { get; set; } = new();
    }
}
