namespace StockStreamPortfolio.Api.Services;

public interface ISymbolValidator
{
    bool IsValidFormat(string symbol);
}
