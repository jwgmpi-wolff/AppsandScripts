namespace StockStreamPortfolio.Api.Models;

public sealed record SymbolValidationResult(
    string Symbol,
    bool IsValidFormat,
    bool ExistsAtProvider,
    string Status,
    string? Error
);
