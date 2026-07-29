namespace StockStreamPortfolio.Api.Contracts;

public sealed record UpdateSettingsRequest(int RefreshIntervalSeconds, bool AggregateDuplicateSymbols, bool AutoAddImportedSymbols);
