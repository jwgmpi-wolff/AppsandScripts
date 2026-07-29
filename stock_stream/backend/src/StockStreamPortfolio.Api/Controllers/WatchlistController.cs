using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using StockStreamPortfolio.Api.Contracts;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Security;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Controllers;

[ApiController]
[Route("watchlist")]
[Authorize(Policy = AuthorizationPolicies.User)]
public sealed class WatchlistController : ControllerBase
{
    private readonly IWatchlistStore _store;
    private readonly ISymbolValidator _symbolValidator;
    private readonly IMarketDataProvider _provider;

    public WatchlistController(IWatchlistStore store, ISymbolValidator symbolValidator, IMarketDataProvider provider)
    {
        _store = store;
        _symbolValidator = symbolValidator;
        _provider = provider;
    }

    [HttpGet]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        var userId = this.GetRequiredUserId();
        return Ok(await _store.GetAsync(userId, cancellationToken));
    }

    [HttpPost]
    public async Task<IActionResult> Add([FromBody] AddWatchlistItemRequest request, CancellationToken cancellationToken)
    {
        var symbol = request.Symbol.Trim().ToUpperInvariant();
        if (!_symbolValidator.IsValidFormat(symbol))
        {
            return BadRequest(new { code = "INVALID_SYMBOL_FORMAT", symbol });
        }

        var validation = await _provider.ValidateSymbolAsync(symbol, cancellationToken);

        var item = new WatchlistItem(
            symbol,
            request.DisplayName,
            request.Notes,
            DateTimeOffset.UtcNow,
            IsProviderSupported: validation.ExistsAtProvider);

        var userId = this.GetRequiredUserId();
        await _store.AddOrUpdateAsync(userId, item, cancellationToken);

        return Ok(new
        {
            item,
            providerValidation = validation
        });
    }

    [HttpDelete("{symbol}")]
    public async Task<IActionResult> Delete(string symbol, CancellationToken cancellationToken)
    {
        var userId = this.GetRequiredUserId();
        await _store.DeleteAsync(userId, symbol.Trim(), cancellationToken);
        return NoContent();
    }

    [HttpPost("validate")]
    public async Task<IActionResult> Validate([FromBody] ValidateWatchlistRequest request, CancellationToken cancellationToken)
    {
        var symbol = request.Symbol.Trim().ToUpperInvariant();
        var isValidFormat = _symbolValidator.IsValidFormat(symbol);
        if (!isValidFormat)
        {
            return Ok(new SymbolValidationResult(symbol, false, false, "InvalidFormat", "Symbol format rejected"));
        }

        var result = await _provider.ValidateSymbolAsync(symbol, cancellationToken);
        return Ok(result);
    }
}
