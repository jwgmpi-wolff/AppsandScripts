using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public interface IColumnLayoutStore
{
    Task<ColumnLayout> GetAsync(string userId, CancellationToken cancellationToken);
    Task SaveAsync(string userId, ColumnLayout layout, CancellationToken cancellationToken);
    ColumnLayout GetDefault();
}
