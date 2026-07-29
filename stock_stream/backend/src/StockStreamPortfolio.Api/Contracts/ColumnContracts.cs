namespace StockStreamPortfolio.Api.Contracts;

public sealed record UpdateColumnLayoutRequest(IReadOnlyList<string> OrderedColumns, IReadOnlyCollection<string> HiddenColumns, string DisplayDensity);
