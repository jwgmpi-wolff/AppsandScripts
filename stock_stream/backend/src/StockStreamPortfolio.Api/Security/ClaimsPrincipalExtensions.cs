using System.Security.Claims;

namespace StockStreamPortfolio.Api.Security;

public static class ClaimsPrincipalExtensions
{
    public static string? GetLocalObjectId(this ClaimsPrincipal principal)
    {
        return principal.FindFirstValue("http://schemas.microsoft.com/identity/claims/objectidentifier")
            ?? principal.FindFirstValue("oid")
            ?? principal.FindFirstValue(ClaimTypes.NameIdentifier);
    }
}
