namespace StockStreamPortfolio.Api.Models;

public sealed record QuoteRecord
{
    public required string Symbol { get; init; }
    public string? DisplayName { get; init; }
    public string DataSource { get; init; } = "Unconfigured";
    public DateTimeOffset RetrievedAtUtc { get; init; }
    public MarketStatus MarketStatus { get; init; } = MarketStatus.Unknown;
    public FreshnessStatus FreshnessStatus { get; init; } = FreshnessStatus.Unknown;
    public bool IsLive { get; init; }
    public string? Message { get; init; }
    public IReadOnlyDictionary<string, string?> Fields { get; init; } = new Dictionary<string, string?>();
    public IReadOnlyCollection<string> MissingFields { get; init; } = Array.Empty<string>();
    public IReadOnlyCollection<string> CalculatedFields { get; init; } = Array.Empty<string>();
    public string? ErrorCode { get; init; }
    public string? ErrorMessage { get; init; }
}
