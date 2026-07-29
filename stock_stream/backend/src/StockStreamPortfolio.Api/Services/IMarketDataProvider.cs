using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public interface IMarketDataProvider
{
    string ProviderName { get; }
    Task<ProviderCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken);
    Task<MarketStatus> GetMarketStatusAsync(CancellationToken cancellationToken);
    Task<SymbolValidationResult> ValidateSymbolAsync(string symbol, CancellationToken cancellationToken);
    Task<IReadOnlyCollection<QuoteRecord>> GetQuotesAsync(IReadOnlyCollection<string> symbols, CancellationToken cancellationToken);
}
