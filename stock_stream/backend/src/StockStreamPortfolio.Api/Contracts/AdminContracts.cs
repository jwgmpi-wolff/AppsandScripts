namespace StockStreamPortfolio.Api.Contracts;

public sealed record UpdateAdminSettingsRequest(
    int MinRefreshSeconds,
    int MaxRefreshSeconds,
    int DefaultRefreshSeconds,
    string ProviderName,
    string ProviderBaseUrl,
    IReadOnlyCollection<string> GlobalTickerList
);
