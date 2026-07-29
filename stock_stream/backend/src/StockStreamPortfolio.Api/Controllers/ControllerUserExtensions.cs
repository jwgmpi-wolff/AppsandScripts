using Microsoft.AspNetCore.Mvc;
using StockStreamPortfolio.Api.Security;

namespace StockStreamPortfolio.Api.Controllers;

internal static class ControllerUserExtensions
{
    public static string GetRequiredUserId(this ControllerBase controller)
    {
        var userId = controller.User.GetLocalObjectId();
        if (string.IsNullOrWhiteSpace(userId))
        {
            throw new UnauthorizedAccessException("Authenticated user object id claim missing.");
        }

        return userId;
    }
}
