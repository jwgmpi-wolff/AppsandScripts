using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using StockStreamPortfolio.Api.Contracts;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Security;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Controllers;

[ApiController]
[Route("csv")]
[Authorize(Policy = AuthorizationPolicies.User)]
public sealed class CsvController : ControllerBase
{
    private readonly ICsvPortfolioParser _parser;
    private readonly IWatchlistStore _watchlistStore;
    private readonly ISymbolValidator _symbolValidator;

    public CsvController(ICsvPortfolioParser parser, IWatchlistStore watchlistStore, ISymbolValidator symbolValidator)
    {
        _parser = parser;
        _watchlistStore = watchlistStore;
        _symbolValidator = symbolValidator;
    }

    [HttpPost("validate")]
    public IActionResult Validate([FromBody] CsvPayloadRequest request)
    {
        var result = _parser.ValidateAndParse(request.CsvText);
        return Ok(result);
    }

    [HttpPost("import")]
    public async Task<IActionResult> Import([FromBody] CsvPayloadRequest request, CancellationToken cancellationToken)
    {
        var result = _parser.ValidateAndParse(request.CsvText);
        if (!result.IsValid)
        {
            return BadRequest(result);
        }

        var addedSymbols = new List<string>();

        if (request.AutoAddSymbolsToWatchlist)
        {
            var userId = this.GetRequiredUserId();

            foreach (var row in result.ParsedRows)
            {
                var symbol = row.Symbol.Trim().ToUpperInvariant();
                if (!_symbolValidator.IsValidFormat(symbol))
                {
                    continue;
                }

                await _watchlistStore.AddOrUpdateAsync(userId, new WatchlistItem(
                    symbol,
                    DisplayName: null,
                    Notes: "Imported baseline symbol",
                    AddedAtUtc: DateTimeOffset.UtcNow,
                    IsProviderSupported: false), cancellationToken);

                addedSymbols.Add(symbol);
            }
        }

        return Ok(new
        {
            importedRows = result.ParsedRows.Count,
            baselineOnly = true,
            autoAddedSymbols = addedSymbols.Distinct(StringComparer.OrdinalIgnoreCase).ToArray()
        });
    }
}
