using StockStreamPortfolio.Api.Models;

namespace StockStreamPortfolio.Api.Services;

public static class RefreshIntervalPolicy
{
    public static int ResolveEffectiveSeconds(UserSettings userSettings, AdminSettings adminSettings, int providerMinSeconds)
    {
        var safeProviderMin = Math.Max(1, providerMinSeconds);
        var safeAdminMin = Math.Max(1, adminSettings.MinRefreshSeconds);
        var safeAdminMax = Math.Max(safeAdminMin, adminSettings.MaxRefreshSeconds);
        var requested = userSettings.RefreshIntervalSeconds;

        var boundedByAdmin = Math.Clamp(requested, safeAdminMin, safeAdminMax);
        return Math.Max(boundedByAdmin, safeProviderMin);
    }
}
