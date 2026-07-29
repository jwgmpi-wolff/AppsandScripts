using System.Collections.Concurrent;
using Microsoft.Extensions.Options;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Options;

namespace StockStreamPortfolio.Api.Services;

public sealed class InMemoryAdminStore : IAdminStore
{
    private readonly object _gate = new();
    private AdminSettings _settings;

    public InMemoryAdminStore(IOptions<RefreshPolicyOptions> refreshOptions, IOptions<MarketDataProviderOptions> providerOptions)
    {
        var refresh = refreshOptions.Value;
        var provider = providerOptions.Value;
        _settings = new AdminSettings(
            refresh.AdminMinSeconds,
            refresh.AdminMaxSeconds,
            refresh.DefaultSeconds,
            provider.ProviderName,
            provider.BaseUrl ?? string.Empty,
            Array.Empty<string>(),
            DateTimeOffset.UtcNow);
    }

    public Task<AdminSettings> GetSettingsAsync(CancellationToken cancellationToken)
    {
        lock (_gate)
        {
            return Task.FromResult(_settings);
        }
    }

    public Task<AdminSettings> SaveSettingsAsync(AdminSettings settings, CancellationToken cancellationToken)
    {
        lock (_gate)
        {
            _settings = settings with { UpdatedAtUtc = DateTimeOffset.UtcNow };
            return Task.FromResult(_settings);
        }
    }
}
