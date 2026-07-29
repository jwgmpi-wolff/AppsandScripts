using System.Text.Json;
using FluentAssertions;
using Microsoft.AspNetCore.SignalR.Client;
using Microsoft.Extensions.DependencyInjection;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Tests.Integration;

public sealed class QuoteHubIntegrationTests : IClassFixture<AuthenticatedApiFactory>
{
    private readonly AuthenticatedApiFactory _factory;

    public QuoteHubIntegrationTests(AuthenticatedApiFactory factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task Hub_Allows_Authenticated_Connection()
    {
        await SeedWatchlistAsync("MSFT");

        await using var connection = CreateConnection();
        await connection.StartAsync();

        connection.State.Should().Be(HubConnectionState.Connected);
    }

    [Fact]
    public async Task Hub_QuoteUpdate_Payload_Matches_Contract()
    {
        await SeedWatchlistAsync("MSFT");

        var updateReceived = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        await using var connection = CreateConnection();

        connection.On<JsonElement>("QuoteUpdate", payload => updateReceived.TrySetResult(payload));
        await connection.StartAsync();

        var payload = await updateReceived.Task.WaitAsync(TimeSpan.FromSeconds(10));

        payload.TryGetProperty("provider", out var provider).Should().BeTrue();
        provider.GetString().Should().Be("IntegrationProvider");

        payload.TryGetProperty("lastSuccessfulLiveUpdateTimestampUtc", out var timestamp).Should().BeTrue();
        timestamp.ValueKind.Should().Be(JsonValueKind.String);

        payload.TryGetProperty("rows", out var rows).Should().BeTrue();
        rows.ValueKind.Should().Be(JsonValueKind.Array);
        rows.GetArrayLength().Should().BeGreaterThan(0);

        var firstRow = rows[0];
        firstRow.TryGetProperty("symbol", out var symbol).Should().BeTrue();
        symbol.GetString().Should().Be("MSFT");

        firstRow.TryGetProperty("isLive", out var isLive).Should().BeTrue();
        isLive.GetBoolean().Should().BeTrue();

        firstRow.TryGetProperty("dataSource", out var dataSource).Should().BeTrue();
        dataSource.GetString().Should().Be("IntegrationProvider");

        firstRow.TryGetProperty("fields", out var fields).Should().BeTrue();
        fields.ValueKind.Should().Be(JsonValueKind.Object);
        fields.TryGetProperty(ColumnNames.Symbol, out _).Should().BeTrue();
    }

    private HubConnection CreateConnection()
    {
        return new HubConnectionBuilder()
            .WithUrl(new Uri(_factory.Server.BaseAddress, "/hubs/quotes"), options =>
            {
                options.HttpMessageHandlerFactory = _ => _factory.Server.CreateHandler();
                options.AccessTokenProvider = () => Task.FromResult<string?>("integration-test-token");
            })
            .WithAutomaticReconnect()
            .Build();
    }

    private async Task SeedWatchlistAsync(string symbol)
    {
        using var scope = _factory.Services.CreateScope();
        var watchlistStore = scope.ServiceProvider.GetRequiredService<IWatchlistStore>();

        await watchlistStore.AddOrUpdateAsync(
            "test-user",
            new WatchlistItem(symbol, "Test", null, DateTimeOffset.UtcNow, IsProviderSupported: true),
            CancellationToken.None);
    }
}
