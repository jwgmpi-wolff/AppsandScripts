namespace StockStreamPortfolio.Api.Models;

public sealed record ProviderCapabilities(
    bool SupportsRealtimeQuotes,
    bool DetectsDelayedQuotes,
    bool SupportsMarketStatus,
    bool SupportsExchangeTradingHours,
    IReadOnlyDictionary<string, IReadOnlyCollection<string>> PerSymbolFieldAvailability
);
