using System.Net;
using FluentAssertions;
using Microsoft.AspNetCore.Mvc.Testing;

namespace StockStreamPortfolio.Api.Tests.Unit;

public sealed class AuthRequiredTests : IClassFixture<WebApplicationFactory<StockStreamPortfolio.Api.Program>>
{
    private readonly WebApplicationFactory<StockStreamPortfolio.Api.Program> _factory;

    public AuthRequiredTests(WebApplicationFactory<StockStreamPortfolio.Api.Program> factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task Watchlist_RequiresAuth()
    {
        using var client = _factory.CreateClient();
        var response = await client.GetAsync("/watchlist");

        response.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Health_IsAnonymous()
    {
        using var client = _factory.CreateClient();
        var response = await client.GetAsync("/health");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
    }
}
