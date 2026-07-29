namespace StockStreamPortfolio.Api.Models;

public sealed record UserSettings(
    int RefreshIntervalSeconds,
    bool AggregateDuplicateSymbols,
    bool AutoAddImportedSymbols,
    DateTimeOffset UpdatedAtUtc
);
