using System.Collections.Concurrent;
using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public sealed class InMemoryColumnLayoutStore : IColumnLayoutStore
{
    private readonly ConcurrentDictionary<string, ColumnLayout> _store = new(StringComparer.OrdinalIgnoreCase);

    public Task<ColumnLayout> GetAsync(string userId, CancellationToken cancellationToken)
    {
        var layout = _store.TryGetValue(userId, out var result) ? result : GetDefault();
        return Task.FromResult(layout);
    }

    public Task SaveAsync(string userId, ColumnLayout layout, CancellationToken cancellationToken)
    {
        _store[userId] = layout;
        return Task.CompletedTask;
    }

    public ColumnLayout GetDefault()
    {
        return new ColumnLayout(ColumnNames.All, new HashSet<string>(), "Compact", DateTimeOffset.UtcNow);
    }
}
