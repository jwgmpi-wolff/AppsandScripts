namespace StockStreamPortfolio.Api.Models;

public sealed record RotatingView(
    string Id,
    string Name,
    IReadOnlyList<string> SelectedColumns,
    string SortBy,
    string SortDirection,
    string? Filter,
    int RefreshIntervalSeconds,
    int RotationIntervalSeconds,
    bool IsPaused,
    DateTimeOffset UpdatedAtUtc
);
