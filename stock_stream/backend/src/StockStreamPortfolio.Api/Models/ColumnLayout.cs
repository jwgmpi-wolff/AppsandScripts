namespace StockStreamPortfolio.Api.Models;

public sealed record ColumnLayout(
    IReadOnlyList<string> OrderedColumns,
    IReadOnlySet<string> HiddenColumns,
    string DisplayDensity,
    DateTimeOffset UpdatedAtUtc
);
