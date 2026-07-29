using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Services;

namespace StockStreamPortfolio.Api.Tests.Integration;

public sealed class AuthenticatedApiFactory : WebApplicationFactory<StockStreamPortfolio.Api.Program>
{
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.ConfigureAppConfiguration((_, configBuilder) =>
        {
            configBuilder.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["RefreshPolicy:AdminMinSeconds"] = "1",
                ["RefreshPolicy:AdminMaxSeconds"] = "5",
                ["RefreshPolicy:DefaultSeconds"] = "1",
                ["MarketDataProvider:MinRefreshSeconds"] = "1",
                ["MarketDataProvider:ProviderName"] = "IntegrationProvider"
            });
        });

        builder.ConfigureServices(services =>
        {
            services.AddAuthentication(options =>
            {
                options.DefaultAuthenticateScheme = TestAuthHandler.SchemeName;
                options.DefaultChallengeScheme = TestAuthHandler.SchemeName;
            }).AddScheme<AuthenticationSchemeOptions, TestAuthHandler>(
                TestAuthHandler.SchemeName,
                _ => { });

            services.RemoveAll<IMarketDataProvider>();
            services.AddSingleton<IMarketDataProvider, DeterministicMarketDataProvider>();
        });
    }
}

internal sealed class TestAuthHandler : AuthenticationHandler<AuthenticationSchemeOptions>
{
    public const string SchemeName = "IntegrationTestAuth";

    public TestAuthHandler(
        IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder)
        : base(options, logger, encoder)
    {
    }

    protected override Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var claims = new[]
        {
            new Claim(ClaimTypes.NameIdentifier, "test-user"),
            new Claim(ClaimTypes.Name, "Integration User"),
            new Claim(ClaimTypes.Role, "User"),
            new Claim("http://schemas.microsoft.com/identity/claims/objectidentifier", "test-user")
        };

        var identity = new ClaimsIdentity(claims, SchemeName);
        var principal = new ClaimsPrincipal(identity);
        var ticket = new AuthenticationTicket(principal, SchemeName);
        return Task.FromResult(AuthenticateResult.Success(ticket));
    }
}

internal sealed class DeterministicMarketDataProvider : IMarketDataProvider
{
    public string ProviderName => "IntegrationProvider";

    public Task<ProviderCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken)
    {
        return Task.FromResult(new ProviderCapabilities(
            SupportsRealtimeQuotes: true,
            DetectsDelayedQuotes: true,
            SupportsMarketStatus: true,
            SupportsExchangeTradingHours: true,
            PerSymbolFieldAvailability: new Dictionary<string, IReadOnlyCollection<string>>()));
    }

    public Task<MarketStatus> GetMarketStatusAsync(CancellationToken cancellationToken)
    {
        return Task.FromResult(MarketStatus.Open);
    }

    public Task<SymbolValidationResult> ValidateSymbolAsync(string symbol, CancellationToken cancellationToken)
    {
        return Task.FromResult(new SymbolValidationResult(symbol, true, true, "Supported", null));
    }

    public Task<IReadOnlyCollection<QuoteRecord>> GetQuotesAsync(IReadOnlyCollection<string> symbols, CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.UtcNow;
        var quotes = symbols.Select(symbol => new QuoteRecord
        {
            Symbol = symbol,
            DataSource = ProviderName,
            RetrievedAtUtc = now,
            IsLive = true,
            MarketStatus = MarketStatus.Open,
            FreshnessStatus = FreshnessStatus.Live,
            Fields = ColumnNames.All.ToDictionary(name => name, _ => (string?)"1"),
            MissingFields = Array.Empty<string>(),
            CalculatedFields = Array.Empty<string>()
        }).ToArray();

        return Task.FromResult<IReadOnlyCollection<QuoteRecord>>(quotes);
    }
}
