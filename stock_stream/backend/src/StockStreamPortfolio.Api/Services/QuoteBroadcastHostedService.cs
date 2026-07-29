using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Options;
using StockStreamPortfolio.Api.Options;

namespace StockStreamPortfolio.Api.Services;

public sealed class QuoteBroadcastHostedService : BackgroundService
{
    private readonly IWatchlistStore _watchlistStore;
    private readonly ISettingsStore _settingsStore;
    private readonly IAdminStore _adminStore;
    private readonly IMarketDataProvider _provider;
    private readonly MarketDataProviderOptions _providerOptions;
    private readonly INoHallucinationGuard _guard;
    private readonly IHubContext<QuoteHub> _hubContext;
    private readonly ILogger<QuoteBroadcastHostedService> _logger;
    private readonly Dictionary<string, DateTimeOffset> _nextEligibleByUser = new(StringComparer.OrdinalIgnoreCase);

    public QuoteBroadcastHostedService(
        IWatchlistStore watchlistStore,
        ISettingsStore settingsStore,
        IAdminStore adminStore,
        IMarketDataProvider provider,
        IOptions<MarketDataProviderOptions> providerOptions,
        INoHallucinationGuard guard,
        IHubContext<QuoteHub> hubContext,
        ILogger<QuoteBroadcastHostedService> logger)
    {
        _watchlistStore = watchlistStore;
        _settingsStore = settingsStore;
        _adminStore = adminStore;
        _provider = provider;
        _providerOptions = providerOptions.Value;
        _guard = guard;
        _hubContext = hubContext;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            var now = DateTimeOffset.UtcNow;
            try
            {
                var all = await _watchlistStore.GetAllAsync(stoppingToken);
                var activeUsers = all.Keys.ToHashSet(StringComparer.OrdinalIgnoreCase);
                foreach (var existingUser in _nextEligibleByUser.Keys.Except(activeUsers, StringComparer.OrdinalIgnoreCase).ToArray())
                {
                    _nextEligibleByUser.Remove(existingUser);
                }

                var adminSettings = await _adminStore.GetSettingsAsync(stoppingToken);
                var providerMinSeconds = _providerOptions.MinRefreshSeconds;

                foreach (var userSet in all)
                {
                    var symbols = userSet.Value.Select(v => v.Symbol).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
                    if (symbols.Length == 0)
                    {
                        continue;
                    }

                    var userId = userSet.Key;
                    var userSettings = await _settingsStore.GetAsync(userId, stoppingToken);
                    var effectiveIntervalSeconds = RefreshIntervalPolicy.ResolveEffectiveSeconds(userSettings, adminSettings, providerMinSeconds);

                    if (_nextEligibleByUser.TryGetValue(userId, out var nextEligibleUtc) && now < nextEligibleUtc)
                    {
                        continue;
                    }

                    var quotes = await _provider.GetQuotesAsync(symbols, stoppingToken);
                    var guarded = _guard.EnforceMany(quotes);

                    await _hubContext.Clients.User(userId).SendAsync("QuoteUpdate", new
                    {
                        provider = _provider.ProviderName,
                        lastSuccessfulLiveUpdateTimestampUtc = guarded.Where(r => r.IsLive).Select(r => r.RetrievedAtUtc).DefaultIfEmpty().Max(),
                        rows = guarded
                    }, stoppingToken);

                    _nextEligibleByUser[userId] = now.AddSeconds(effectiveIntervalSeconds);
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Quote broadcast cycle failed.");
            }

            var delay = CalculateDelay(DateTimeOffset.UtcNow);
            await Task.Delay(delay, stoppingToken);
        }
    }

    private TimeSpan CalculateDelay(DateTimeOffset now)
    {
        if (_nextEligibleByUser.Count == 0)
        {
            return TimeSpan.FromSeconds(1);
        }

        var nextDueUtc = _nextEligibleByUser.Values.Min();
        var untilNext = nextDueUtc - now;

        if (untilNext <= TimeSpan.Zero)
        {
            return TimeSpan.FromMilliseconds(250);
        }

        // Keep delays short enough to react to new watchlist entries without long sleeps.
        return untilNext > TimeSpan.FromSeconds(5) ? TimeSpan.FromSeconds(5) : untilNext;
    }
}
