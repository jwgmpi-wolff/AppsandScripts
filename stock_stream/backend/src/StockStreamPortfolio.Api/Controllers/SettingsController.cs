using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;
using StockStreamPortfolio.Api.Contracts;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Options;
using StockStreamPortfolio.Api.Security;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Controllers;

[ApiController]
[Route("settings")]
[Authorize(Policy = AuthorizationPolicies.User)]
public sealed class SettingsController : ControllerBase
{
    private readonly ISettingsStore _store;
    private readonly RefreshPolicyOptions _refreshPolicy;

    public SettingsController(ISettingsStore store, IOptions<RefreshPolicyOptions> refreshPolicy)
    {
        _store = store;
        _refreshPolicy = refreshPolicy.Value;
    }

    [HttpGet]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        var userId = this.GetRequiredUserId();
        var settings = await _store.GetAsync(userId, cancellationToken);
        return Ok(new
        {
            settings,
            minAllowedSeconds = _refreshPolicy.AdminMinSeconds,
            maxAllowedSeconds = _refreshPolicy.AdminMaxSeconds
        });
    }

    [HttpPut]
    public async Task<IActionResult> Put([FromBody] UpdateSettingsRequest request, CancellationToken cancellationToken)
    {
        if (request.RefreshIntervalSeconds < _refreshPolicy.AdminMinSeconds || request.RefreshIntervalSeconds > _refreshPolicy.AdminMaxSeconds)
        {
            return BadRequest(new
            {
                code = "INVALID_INTERVAL",
                message = $"Refresh interval must be between {_refreshPolicy.AdminMinSeconds} and {_refreshPolicy.AdminMaxSeconds} seconds."
            });
        }

        var userId = this.GetRequiredUserId();
        var settings = new UserSettings(
            request.RefreshIntervalSeconds,
            request.AggregateDuplicateSymbols,
            request.AutoAddImportedSymbols,
            DateTimeOffset.UtcNow);

        await _store.SaveAsync(userId, settings, cancellationToken);
        return Ok(settings);
    }
}
