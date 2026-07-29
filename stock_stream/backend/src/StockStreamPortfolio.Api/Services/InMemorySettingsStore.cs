using System.Collections.Concurrent;
using Microsoft.Extensions.Options;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Options;

namespace StockStreamPortfolio.Api.Services;

public sealed class InMemorySettingsStore : ISettingsStore
{
    private readonly ConcurrentDictionary<string, UserSettings> _store = new(StringComparer.OrdinalIgnoreCase);
    private readonly RefreshPolicyOptions _refreshPolicyOptions;

    public InMemorySettingsStore(IOptions<RefreshPolicyOptions> refreshPolicyOptions)
    {
        _refreshPolicyOptions = refreshPolicyOptions.Value;
    }

    public Task<UserSettings> GetAsync(string userId, CancellationToken cancellationToken)
    {
        if (_store.TryGetValue(userId, out var settings))
        {
            return Task.FromResult(settings);
        }

        return Task.FromResult(new UserSettings(
            _refreshPolicyOptions.DefaultSeconds,
            AggregateDuplicateSymbols: false,
            AutoAddImportedSymbols: true,
            UpdatedAtUtc: DateTimeOffset.UtcNow));
    }

    public Task SaveAsync(string userId, UserSettings settings, CancellationToken cancellationToken)
    {
        _store[userId] = settings;
        return Task.CompletedTask;
    }
}
