using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using StockStreamPortfolio.Api.Security;

namespace StockStreamPortfolio.Api.Services;

[Authorize(Policy = AuthorizationPolicies.User)]
public sealed class QuoteHub : Hub
{
}
