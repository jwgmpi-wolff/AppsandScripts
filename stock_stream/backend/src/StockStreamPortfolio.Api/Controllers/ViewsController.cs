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
[Route("views")]
[Authorize(Policy = AuthorizationPolicies.User)]
public sealed class ViewsController : ControllerBase
{
    private readonly IRotatingViewStore _store;
    private readonly RefreshPolicyOptions _refreshPolicy;

    public ViewsController(IRotatingViewStore store, IOptions<RefreshPolicyOptions> refreshPolicy)
    {
        _store = store;
        _refreshPolicy = refreshPolicy.Value;
    }

    [HttpGet]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        var userId = this.GetRequiredUserId();
        return Ok(await _store.GetAsync(userId, cancellationToken));
    }

    [HttpPost]
    public async Task<IActionResult> Create([FromBody] UpsertViewRequest request, CancellationToken cancellationToken)
    {
        return Ok(await Save(request, cancellationToken));
    }

    [HttpPut("{id}")]
    public async Task<IActionResult> Update(string id, [FromBody] UpsertViewRequest request, CancellationToken cancellationToken)
    {
        return Ok(await Save(request with { Id = id }, cancellationToken));
    }

    [HttpDelete("{id}")]
    public async Task<IActionResult> Delete(string id, CancellationToken cancellationToken)
    {
        var userId = this.GetRequiredUserId();
        await _store.DeleteAsync(userId, id, cancellationToken);
        return NoContent();
    }

    private async Task<RotatingView> Save(UpsertViewRequest request, CancellationToken cancellationToken)
    {
        if (request.RefreshIntervalSeconds < _refreshPolicy.AdminMinSeconds || request.RefreshIntervalSeconds > _refreshPolicy.AdminMaxSeconds)
        {
            throw new BadHttpRequestException($"Refresh interval must be between {_refreshPolicy.AdminMinSeconds} and {_refreshPolicy.AdminMaxSeconds}.");
        }

        var id = string.IsNullOrWhiteSpace(request.Id) ? Guid.NewGuid().ToString("N") : request.Id;
        var view = new RotatingView(
            id,
            request.Name,
            request.SelectedColumns,
            request.SortBy,
            request.SortDirection,
            request.Filter,
            request.RefreshIntervalSeconds,
            request.RotationIntervalSeconds,
            request.IsPaused,
            DateTimeOffset.UtcNow);

        var userId = this.GetRequiredUserId();
        return await _store.UpsertAsync(userId, view, cancellationToken);
    }
}
