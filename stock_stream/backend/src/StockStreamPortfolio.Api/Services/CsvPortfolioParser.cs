using System.Globalization;
using Microsoft.VisualBasic.FileIO;
using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public sealed class CsvPortfolioParser : ICsvPortfolioParser
{
    private static readonly string[] RequiredColumns = ColumnNames.All;

    public CsvValidationResult ValidateAndParse(string csvText)
    {
        if (string.IsNullOrWhiteSpace(csvText))
        {
            return new CsvValidationResult(false, [new CsvValidationError(0, "CSV", "CSV content is empty")], Array.Empty<ImportedPortfolioRow>());
        }

        using var reader = new StringReader(csvText);
        using var parser = new TextFieldParser(reader)
        {
            TextFieldType = FieldType.Delimited,
            HasFieldsEnclosedInQuotes = true
        };
        parser.SetDelimiters(",");

        if (parser.EndOfData)
        {
            return new CsvValidationResult(false, [new CsvValidationError(0, "CSV", "CSV does not contain headers")], Array.Empty<ImportedPortfolioRow>());
        }

        var headers = parser.ReadFields() ?? Array.Empty<string>();
        var missing = RequiredColumns.Where(required => !headers.Contains(required, StringComparer.OrdinalIgnoreCase)).ToArray();
        if (missing.Length > 0)
        {
            return new CsvValidationResult(false, missing.Select(m => new CsvValidationError(0, m, "Required column missing")).ToArray(), Array.Empty<ImportedPortfolioRow>());
        }

        var headerMap = headers
            .Select((value, index) => new { value, index })
            .ToDictionary(x => x.value, x => x.index, StringComparer.OrdinalIgnoreCase);

        var errors = new List<CsvValidationError>();
        var rows = new List<ImportedPortfolioRow>();
        var rowNumber = 1;

        while (!parser.EndOfData)
        {
            rowNumber++;
            var fields = parser.ReadFields() ?? Array.Empty<string>();
            var raw = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            foreach (var required in RequiredColumns)
            {
                var value = headerMap.TryGetValue(required, out var idx) && idx < fields.Length ? fields[idx] : string.Empty;
                raw[required] = value;
            }

            var symbol = raw[ColumnNames.Symbol].Trim();
            if (string.IsNullOrWhiteSpace(symbol))
            {
                errors.Add(new CsvValidationError(rowNumber, ColumnNames.Symbol, "Symbol is required"));
                continue;
            }

            if (!TryParseNullableDecimal(raw[ColumnNames.Quantity], out var quantity))
            {
                errors.Add(new CsvValidationError(rowNumber, ColumnNames.Quantity, "Quantity must be numeric when provided"));
            }

            if (!TryParseNullableDecimal(raw[ColumnNames.PurchasePrice], out var purchasePrice))
            {
                errors.Add(new CsvValidationError(rowNumber, ColumnNames.PurchasePrice, "Purchase Price must be numeric when provided"));
            }

            rows.Add(new ImportedPortfolioRow(
                rowNumber,
                raw,
                symbol,
                quantity,
                purchasePrice,
                raw[ColumnNames.Account],
                IsBaselineValue: true));
        }

        return new CsvValidationResult(errors.Count == 0, errors, rows);
    }

    private static bool TryParseNullableDecimal(string value, out decimal? result)
    {
        result = null;
        if (string.IsNullOrWhiteSpace(value))
        {
            return true;
        }

        var normalized = value.Replace("$", string.Empty, StringComparison.Ordinal).Replace(",", string.Empty, StringComparison.Ordinal);
        if (decimal.TryParse(normalized, NumberStyles.Any, CultureInfo.InvariantCulture, out var parsed))
        {
            result = parsed;
            return true;
        }

        return false;
    }
}
