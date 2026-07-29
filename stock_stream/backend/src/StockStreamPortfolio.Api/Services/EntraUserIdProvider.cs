using Microsoft.AspNetCore.SignalR;
using StockStreamPortfolio.Api.Security;

namespace StockStreamPortfolio.Api.Services;

public sealed class EntraUserIdProvider : IUserIdProvider
{
    public string? GetUserId(HubConnectionContext connection)
    {
        return connection.User?.GetLocalObjectId();
    }
}
