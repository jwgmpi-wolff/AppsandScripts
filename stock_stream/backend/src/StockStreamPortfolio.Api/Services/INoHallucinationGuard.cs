using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public interface INoHallucinationGuard
{
    QuoteRecord Enforce(QuoteRecord input);
    IReadOnlyCollection<QuoteRecord> EnforceMany(IReadOnlyCollection<QuoteRecord> input);
}
