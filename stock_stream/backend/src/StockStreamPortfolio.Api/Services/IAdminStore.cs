using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public interface IAdminStore
{
    Task<AdminSettings> GetSettingsAsync(CancellationToken cancellationToken);
    Task<AdminSettings> SaveSettingsAsync(AdminSettings settings, CancellationToken cancellationToken);
}
