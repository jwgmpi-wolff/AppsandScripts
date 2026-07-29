namespace StockStreamPortfolio.Api.Models;

public sealed record CsvValidationError(int RowNumber, string Column, string Message);

public sealed record ImportedPortfolioRow(
    int RowNumber,
    IReadOnlyDictionary<string, string> RawValues,
    string Symbol,
    decimal? Quantity,
    decimal? PurchasePrice,
    string? Account,
    bool IsBaselineValue
);

public sealed record CsvValidationResult(
    bool IsValid,
    IReadOnlyCollection<CsvValidationError> Errors,
    IReadOnlyCollection<ImportedPortfolioRow> ParsedRows
);
