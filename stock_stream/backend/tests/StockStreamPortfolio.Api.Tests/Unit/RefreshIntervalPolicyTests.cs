using FluentAssertions;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Tests.Unit;

public sealed class RefreshIntervalPolicyTests
{
    [Fact]
    public void ResolveEffectiveSeconds_Respects_Admin_Bounds_And_Provider_Minimum()
    {
        var user = new UserSettings(RefreshIntervalSeconds: 1, AggregateDuplicateSymbols: false, AutoAddImportedSymbols: true, UpdatedAtUtc: DateTimeOffset.UtcNow);
        var admin = new AdminSettings(
            MinRefreshSeconds: 3,
            MaxRefreshSeconds: 30,
            DefaultRefreshSeconds: 10,
            ProviderName: "Test",
            ProviderBaseUrl: string.Empty,
            GlobalTickerList: Array.Empty<string>(),
            UpdatedAtUtc: DateTimeOffset.UtcNow);

        var effective = RefreshIntervalPolicy.ResolveEffectiveSeconds(user, admin, providerMinSeconds: 5);

        effective.Should().Be(5);
    }

    [Fact]
    public void ResolveEffectiveSeconds_Respects_Admin_Max_When_User_Request_Is_Too_High()
    {
        var user = new UserSettings(RefreshIntervalSeconds: 120, AggregateDuplicateSymbols: false, AutoAddImportedSymbols: true, UpdatedAtUtc: DateTimeOffset.UtcNow);
        var admin = new AdminSettings(
            MinRefreshSeconds: 2,
            MaxRefreshSeconds: 20,
            DefaultRefreshSeconds: 10,
            ProviderName: "Test",
            ProviderBaseUrl: string.Empty,
            GlobalTickerList: Array.Empty<string>(),
            UpdatedAtUtc: DateTimeOffset.UtcNow);

        var effective = RefreshIntervalPolicy.ResolveEffectiveSeconds(user, admin, providerMinSeconds: 1);

        effective.Should().Be(20);
    }
}
