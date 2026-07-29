namespace StockStreamPortfolio.Api.Models;

public sealed record AdminSettings(
    int MinRefreshSeconds,
    int MaxRefreshSeconds,
    int DefaultRefreshSeconds,
    string ProviderName,
    string ProviderBaseUrl,
    IReadOnlyCollection<string> GlobalTickerList,
    DateTimeOffset UpdatedAtUtc
);
