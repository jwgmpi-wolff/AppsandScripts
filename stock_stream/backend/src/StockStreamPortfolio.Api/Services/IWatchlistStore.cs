using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public interface IWatchlistStore
{
    Task<IReadOnlyCollection<WatchlistItem>> GetAsync(string userId, CancellationToken cancellationToken);
    Task<IReadOnlyDictionary<string, IReadOnlyCollection<WatchlistItem>>> GetAllAsync(CancellationToken cancellationToken);
    Task AddOrUpdateAsync(string userId, WatchlistItem item, CancellationToken cancellationToken);
    Task DeleteAsync(string userId, string symbol, CancellationToken cancellationToken);
}
