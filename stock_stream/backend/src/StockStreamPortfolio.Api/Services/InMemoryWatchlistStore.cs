using System.Collections.Concurrent;
using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public sealed class InMemoryWatchlistStore : IWatchlistStore
{
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<string, WatchlistItem>> _store = new(StringComparer.OrdinalIgnoreCase);

    public Task<IReadOnlyCollection<WatchlistItem>> GetAsync(string userId, CancellationToken cancellationToken)
    {
        var items = _store.TryGetValue(userId, out var values)
            ? values.Values.OrderBy(v => v.Symbol, StringComparer.OrdinalIgnoreCase).ToArray()
            : Array.Empty<WatchlistItem>();

        return Task.FromResult<IReadOnlyCollection<WatchlistItem>>(items);
    }

    public Task<IReadOnlyDictionary<string, IReadOnlyCollection<WatchlistItem>>> GetAllAsync(CancellationToken cancellationToken)
    {
        var result = _store.ToDictionary(
            kvp => kvp.Key,
            kvp => (IReadOnlyCollection<WatchlistItem>)kvp.Value.Values.OrderBy(v => v.Symbol, StringComparer.OrdinalIgnoreCase).ToArray(),
            StringComparer.OrdinalIgnoreCase);

        return Task.FromResult<IReadOnlyDictionary<string, IReadOnlyCollection<WatchlistItem>>>(result);
    }

    public Task AddOrUpdateAsync(string userId, WatchlistItem item, CancellationToken cancellationToken)
    {
        var userSet = _store.GetOrAdd(userId, _ => new ConcurrentDictionary<string, WatchlistItem>(StringComparer.OrdinalIgnoreCase));
        userSet[item.Symbol] = item;
        return Task.CompletedTask;
    }

    public Task DeleteAsync(string userId, string symbol, CancellationToken cancellationToken)
    {
        if (_store.TryGetValue(userId, out var values))
        {
            values.TryRemove(symbol, out _);
        }

        return Task.CompletedTask;
    }
}
