namespace StockStreamPortfolio.Api.Contracts;

public sealed record UpsertViewRequest(
    string? Id,
    string Name,
    IReadOnlyList<string> SelectedColumns,
    string SortBy,
    string SortDirection,
    string? Filter,
    int RefreshIntervalSeconds,
    int RotationIntervalSeconds,
    bool IsPaused
);
