using FluentAssertions;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Tests.Unit;

public sealed class NoHallucinationGuardTests
{
    [Fact]
    public void Enforce_SetsIsLiveFalse_WhenMarketClosed()
    {
        var guard = new NoHallucinationGuard();

        var record = new QuoteRecord
        {
            Symbol = "MSFT",
            DataSource = "Test",
            RetrievedAtUtc = DateTimeOffset.UtcNow,
            MarketStatus = MarketStatus.Closed,
            FreshnessStatus = FreshnessStatus.Live,
            IsLive = true,
            Fields = new Dictionary<string, string?>()
        };

        var result = guard.Enforce(record);
        result.IsLive.Should().BeFalse();
    }

    [Fact]
    public void Enforce_DoesNotInventMissingFieldValues()
    {
        var guard = new NoHallucinationGuard();

        var record = new QuoteRecord
        {
            Symbol = "NVDA",
            DataSource = "Test",
            RetrievedAtUtc = DateTimeOffset.UtcNow,
            MarketStatus = MarketStatus.Unavailable,
            FreshnessStatus = FreshnessStatus.Unavailable,
            IsLive = false,
            Fields = new Dictionary<string, string?>
            {
                [ColumnNames.Last] = null
            }
        };

        var result = guard.Enforce(record);
        result.Fields[ColumnNames.Last].Should().BeNull();
    }
}
