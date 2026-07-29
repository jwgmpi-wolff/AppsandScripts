namespace StockStreamPortfolio.Api.Contracts;

public sealed record AddWatchlistItemRequest(string Symbol, string? DisplayName, string? Notes);
public sealed record ValidateWatchlistRequest(string Symbol);
