using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Security;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Controllers;

[ApiController]
[Route("market-status")]
[Authorize(Policy = AuthorizationPolicies.User)]
public sealed class MarketStatusController : ControllerBase
{
    private readonly IMarketDataProvider _provider;

    public MarketStatusController(IMarketDataProvider provider)
    {
        _provider = provider;
    }

    [HttpGet]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        var status = await _provider.GetMarketStatusAsync(cancellationToken);
        var message = status == MarketStatus.Open ? "Market open" : "Market closed or live data unavailable.";

        return Ok(new
        {
            provider = _provider.ProviderName,
            marketStatus = status,
            message,
            retrievedAtUtc = DateTimeOffset.UtcNow
        });
    }
}
