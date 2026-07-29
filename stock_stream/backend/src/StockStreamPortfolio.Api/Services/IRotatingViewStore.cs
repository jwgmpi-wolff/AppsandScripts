using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public interface IRotatingViewStore
{
    Task<IReadOnlyCollection<RotatingView>> GetAsync(string userId, CancellationToken cancellationToken);
    Task<RotatingView?> GetByIdAsync(string userId, string id, CancellationToken cancellationToken);
    Task<RotatingView> UpsertAsync(string userId, RotatingView view, CancellationToken cancellationToken);
    Task DeleteAsync(string userId, string id, CancellationToken cancellationToken);
}
