namespace StockStreamPortfolio.Api.Models;

public sealed record WatchlistItem(
    string Symbol,
    string? DisplayName,
    string? Notes,
    DateTimeOffset AddedAtUtc,
    bool IsProviderSupported
);
