namespace StockStreamPortfolio.Api.Contracts;

public sealed record CsvPayloadRequest(string CsvText, bool AutoAddSymbolsToWatchlist);
