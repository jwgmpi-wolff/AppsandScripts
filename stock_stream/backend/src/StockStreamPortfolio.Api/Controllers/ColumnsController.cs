using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using StockStreamPortfolio.Api.Contracts;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Security;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Controllers;

[ApiController]
[Route("columns")]
[Authorize(Policy = AuthorizationPolicies.User)]
public sealed class ColumnsController : ControllerBase
{
    private readonly IColumnLayoutStore _store;

    public ColumnsController(IColumnLayoutStore store)
    {
        _store = store;
    }

    [HttpGet]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        var userId = this.GetRequiredUserId();
        return Ok(await _store.GetAsync(userId, cancellationToken));
    }

    [HttpPut("layout")]
    public async Task<IActionResult> PutLayout([FromBody] UpdateColumnLayoutRequest request, CancellationToken cancellationToken)
    {
        if (request.OrderedColumns.Count == 0)
        {
            return BadRequest(new { code = "EMPTY_LAYOUT" });
        }

        if (request.OrderedColumns.Any(c => !ColumnNames.All.Contains(c, StringComparer.OrdinalIgnoreCase)))
        {
            return BadRequest(new { code = "UNKNOWN_COLUMN" });
        }

        var layout = new ColumnLayout(
            request.OrderedColumns,
            new HashSet<string>(request.HiddenColumns, StringComparer.OrdinalIgnoreCase),
            request.DisplayDensity,
            DateTimeOffset.UtcNow);

        var userId = this.GetRequiredUserId();
        await _store.SaveAsync(userId, layout, cancellationToken);
        return Ok(layout);
    }

    [HttpPost("layout/reset")]
    public async Task<IActionResult> Reset(CancellationToken cancellationToken)
    {
        var userId = this.GetRequiredUserId();
        var layout = _store.GetDefault();
        await _store.SaveAsync(userId, layout, cancellationToken);
        return Ok(layout);
    }
}
