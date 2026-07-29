using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public sealed class NoHallucinationGuard : INoHallucinationGuard
{
    public QuoteRecord Enforce(QuoteRecord input)
    {
        if (input.IsLive && input.FreshnessStatus != FreshnessStatus.Live)
        {
            return input with { IsLive = false };
        }

        if (input.MarketStatus != MarketStatus.Open)
        {
            return input with { IsLive = false };
        }

        return input;
    }

    public IReadOnlyCollection<QuoteRecord> EnforceMany(IReadOnlyCollection<QuoteRecord> input)
    {
        return input.Select(Enforce).ToArray();
    }
}
