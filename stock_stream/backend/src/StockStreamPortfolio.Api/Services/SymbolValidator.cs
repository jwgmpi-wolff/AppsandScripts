using System.Text.RegularExpressions;

namespace StockStreamPortfolio.Api.Services;

public sealed class SymbolValidator : ISymbolValidator
{
    private static readonly Regex SymbolRegex = new("^[A-Za-z0-9._-]{1,15}$", RegexOptions.Compiled);

    public bool IsValidFormat(string symbol)
    {
        if (string.IsNullOrWhiteSpace(symbol))
        {
            return false;
        }

        return SymbolRegex.IsMatch(symbol.Trim());
    }
}
