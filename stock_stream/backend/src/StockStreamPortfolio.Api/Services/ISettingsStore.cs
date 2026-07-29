using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public interface ISettingsStore
{
    Task<UserSettings> GetAsync(string userId, CancellationToken cancellationToken);
    Task SaveAsync(string userId, UserSettings settings, CancellationToken cancellationToken);
}
