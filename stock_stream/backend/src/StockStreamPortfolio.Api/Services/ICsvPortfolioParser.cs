using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public interface ICsvPortfolioParser
{
    CsvValidationResult ValidateAndParse(string csvText);
}
