using System.Collections.Concurrent;
using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public sealed class InMemoryRotatingViewStore : IRotatingViewStore
{
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<string, RotatingView>> _store = new(StringComparer.OrdinalIgnoreCase);

    public Task<IReadOnlyCollection<RotatingView>> GetAsync(string userId, CancellationToken cancellationToken)
    {
        var values = _store.TryGetValue(userId, out var set)
            ? set.Values.OrderBy(v => v.Name, StringComparer.OrdinalIgnoreCase).ToArray()
            : Array.Empty<RotatingView>();

        return Task.FromResult<IReadOnlyCollection<RotatingView>>(values);
    }

    public Task<RotatingView?> GetByIdAsync(string userId, string id, CancellationToken cancellationToken)
    {
        var value = _store.TryGetValue(userId, out var set) && set.TryGetValue(id, out var existing) ? existing : null;
        return Task.FromResult(value);
    }

    public Task<RotatingView> UpsertAsync(string userId, RotatingView view, CancellationToken cancellationToken)
    {
        var set = _store.GetOrAdd(userId, _ => new ConcurrentDictionary<string, RotatingView>(StringComparer.OrdinalIgnoreCase));
        set[view.Id] = view;
        return Task.FromResult(view);
    }

    public Task DeleteAsync(string userId, string id, CancellationToken cancellationToken)
    {
        if (_store.TryGetValue(userId, out var set))
        {
            set.TryRemove(id, out _);
        }

        return Task.CompletedTask;
    }
}
