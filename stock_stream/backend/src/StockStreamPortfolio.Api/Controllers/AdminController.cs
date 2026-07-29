using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using StockStreamPortfolio.Api.Contracts;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Security;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Controllers;

[ApiController]
[Route("admin")]
[Authorize(Policy = AuthorizationPolicies.Admin)]
public sealed class AdminController : ControllerBase
{
    private readonly IAdminStore _store;

    public AdminController(IAdminStore store)
    {
        _store = store;
    }

    [HttpGet("settings")]
    public async Task<IActionResult> GetSettings(CancellationToken cancellationToken)
    {
        return Ok(await _store.GetSettingsAsync(cancellationToken));
    }

    [HttpPut("settings")]
    public async Task<IActionResult> UpdateSettings([FromBody] UpdateAdminSettingsRequest request, CancellationToken cancellationToken)
    {
        if (request.MinRefreshSeconds <= 0 || request.MaxRefreshSeconds < request.MinRefreshSeconds)
        {
            return BadRequest(new { code = "INVALID_REFRESH_BOUNDS" });
        }

        var settings = new AdminSettings(
            request.MinRefreshSeconds,
            request.MaxRefreshSeconds,
            request.DefaultRefreshSeconds,
            request.ProviderName,
            request.ProviderBaseUrl,
            request.GlobalTickerList,
            DateTimeOffset.UtcNow);

        return Ok(await _store.SaveSettingsAsync(settings, cancellationToken));
    }
}
