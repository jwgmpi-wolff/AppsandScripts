using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using StockStreamPortfolio.Api.Security;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Controllers;

[ApiController]
[Route("quotes")]
[Authorize(Policy = AuthorizationPolicies.User)]
public sealed class QuotesController : ControllerBase
{
    private readonly IMarketDataProvider _provider;
    private readonly INoHallucinationGuard _guard;

    public QuotesController(IMarketDataProvider provider, INoHallucinationGuard guard)
    {
        _provider = provider;
        _guard = guard;
    }

    [HttpGet]
    public async Task<IActionResult> Get([FromQuery] string symbols, CancellationToken cancellationToken)
    {
        var parsedSymbols = symbols
            .Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Select(s => s.ToUpperInvariant())
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

        if (parsedSymbols.Length == 0)
        {
            return BadRequest(new { code = "MISSING_SYMBOLS", message = "Provide at least one symbol." });
        }

        var records = await _provider.GetQuotesAsync(parsedSymbols, cancellationToken);
        var guarded = _guard.EnforceMany(records);

        var lastLiveUpdateUtc = guarded.Where(r => r.IsLive).Select(r => r.RetrievedAtUtc).DefaultIfEmpty().Max();

        return Ok(new
        {
            provider = _provider.ProviderName,
            lastSuccessfulLiveUpdateTimestampUtc = lastLiveUpdateUtc == default ? (DateTimeOffset?)null : lastLiveUpdateUtc,
            rows = guarded
        });
    }
}
