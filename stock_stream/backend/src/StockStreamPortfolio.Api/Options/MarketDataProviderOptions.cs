namespace StockStreamPortfolio.Api.Options;

public sealed class MarketDataProviderOptions
{
    public const string SectionName = "MarketDataProvider";

    public string ProviderName { get; set; } = "Unconfigured";
    public string? BaseUrl { get; set; }
    public string? ApiKeySecretName { get; set; }
    public int TimeoutSeconds { get; set; } = 10;
    public int MaxSymbolsPerRequest { get; set; } = 25;
    public int MinRefreshSeconds { get; set; } = 5;
}
