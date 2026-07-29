using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Globalization;
using Azure.Identity;
using Azure.Security.KeyVault.Secrets;
using Microsoft.Extensions.Options;
using StockStreamPortfolio.Api.Models;
using StockStreamPortfolio.Api.Options;

namespace StockStreamPortfolio.Api.Services;

public sealed class ConfigurableMarketDataProvider : IMarketDataProvider
{
    private const string FinnhubQuoteEndpoint = "https://finnhub.io/api/v1/quote";
    private const string FinnhubMetricEndpoint = "https://finnhub.io/api/v1/stock/metric";
    private const string YahooQuoteEndpoint = "https://query1.finance.yahoo.com/v7/finance/quote";
    private const string StooqEndpoint = "https://stooq.com/q/l/";

    private static readonly IReadOnlyDictionary<string, string[]> FieldAliases = new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase)
    {
        [ColumnNames.Last] = ["last", "price", "lastPrice", "regularMarketPrice", "currentPrice"],
        [ColumnNames.Bid] = ["bid", "bidPrice", "regularMarketBid"],
        [ColumnNames.Ask] = ["ask", "askPrice", "regularMarketAsk"],
        [ColumnNames.Chg] = ["chg", "change", "regularMarketChange"],
        [ColumnNames.Volume] = ["volume", "regularMarketVolume"],
        [ColumnNames.PrevClose] = ["prevClose", "previousClose", "regularMarketPreviousClose"],
        [ColumnNames.DayRange] = ["dayRange", "regularMarketDayRange"],
        [ColumnNames.FiftyTwoWkRange] = ["fiftyTwoWeekRange", "52WeekRange", "fiftyTwoWkRange"],
        [ColumnNames.CloseValue] = ["close", "closePrice", "regularMarketClose"],
        ["AfterHoursPrice"] = ["afterHoursPrice", "postMarketPrice", "extendedPrice", "extendedHoursPrice"],
        ["AfterHoursChange"] = ["afterHoursChange", "postMarketChange", "extendedChange", "extendedHoursChange"],
        ["AfterHoursChangePercent"] = ["afterHoursChangePercent", "postMarketChangePercent", "extendedChangePercent", "extendedHoursChangePercent"]
    };

    private readonly HttpClient _httpClient;
    private readonly IConfiguration _configuration;
    private readonly MarketDataProviderOptions _options;
    private readonly ILogger<ConfigurableMarketDataProvider> _logger;

    public ConfigurableMarketDataProvider(
        HttpClient httpClient,
        IConfiguration configuration,
        IOptions<MarketDataProviderOptions> options,
        ILogger<ConfigurableMarketDataProvider> logger)
    {
        _httpClient = httpClient;
        _configuration = configuration;
        _options = options.Value;
        _logger = logger;

        if (!string.IsNullOrWhiteSpace(_options.BaseUrl))
        {
            _httpClient.BaseAddress = new Uri(_options.BaseUrl);
        }

        _httpClient.Timeout = TimeSpan.FromSeconds(_options.TimeoutSeconds);
    }

    public string ProviderName => _options.ProviderName;

    public Task<ProviderCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken)
    {
        var capabilities = new ProviderCapabilities(
            SupportsRealtimeQuotes: true,
            DetectsDelayedQuotes: true,
            SupportsMarketStatus: true,
            SupportsExchangeTradingHours: true,
            PerSymbolFieldAvailability: new Dictionary<string, IReadOnlyCollection<string>>());

        return Task.FromResult(capabilities);
    }

    public async Task<MarketStatus> GetMarketStatusAsync(CancellationToken cancellationToken)
    {
        if (ShouldUseYahooFallback())
        {
            return MarketStatus.Unknown;
        }

        var request = new HttpRequestMessage(HttpMethod.Get, "/market-status");
        var key = await ResolveApiKeyAsync(cancellationToken);
        if (string.IsNullOrWhiteSpace(key) || _httpClient.BaseAddress is null)
        {
            return MarketStatus.Unavailable;
        }

        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        using var response = await _httpClient.SendAsync(request, cancellationToken);
        if (response.StatusCode == HttpStatusCode.TooManyRequests)
        {
            return MarketStatus.Unavailable;
        }

        if (!response.IsSuccessStatusCode)
        {
            return MarketStatus.Unavailable;
        }

        var content = await response.Content.ReadAsStringAsync(cancellationToken);
        if (content.Contains("open", StringComparison.OrdinalIgnoreCase))
        {
            return MarketStatus.Open;
        }

        if (content.Contains("closed", StringComparison.OrdinalIgnoreCase))
        {
            return MarketStatus.Closed;
        }

        return MarketStatus.Unknown;
    }

    public async Task<SymbolValidationResult> ValidateSymbolAsync(string symbol, CancellationToken cancellationToken)
    {
        if (ShouldUseYahooFallback())
        {
            var quotes = await GetYahooQuotesAsync([symbol], cancellationToken);
            var exists = quotes.Any(q => !string.Equals(q.ErrorCode, "UNAVAILABLE", StringComparison.OrdinalIgnoreCase));
            return new SymbolValidationResult(symbol, true, exists, exists ? "Supported" : "Unavailable", exists ? null : "Symbol not found at provider");
        }

        var request = new HttpRequestMessage(HttpMethod.Get, $"/symbols/{Uri.EscapeDataString(symbol)}");
        var key = await ResolveApiKeyAsync(cancellationToken);

        if (string.IsNullOrWhiteSpace(key) || _httpClient.BaseAddress is null)
        {
            return new SymbolValidationResult(symbol, true, false, "Unavailable", "Provider not configured");
        }

        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);

        try
        {
            using var response = await _httpClient.SendAsync(request, cancellationToken);
            if (response.StatusCode == HttpStatusCode.NotFound)
            {
                return new SymbolValidationResult(symbol, true, false, "Unsupported", "Symbol not found at provider");
            }

            if (!response.IsSuccessStatusCode)
            {
                return new SymbolValidationResult(symbol, true, false, "Unavailable", $"Provider error: {(int)response.StatusCode}");
            }

            return new SymbolValidationResult(symbol, true, true, "Supported", null);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed symbol validation for {Symbol}", symbol);
            return new SymbolValidationResult(symbol, true, false, "Unavailable", ex.Message);
        }
    }

    public async Task<IReadOnlyCollection<QuoteRecord>> GetQuotesAsync(IReadOnlyCollection<string> symbols, CancellationToken cancellationToken)
    {
        if (ShouldUseFinnhubProvider())
        {
            // Finnhub does not expose a bulk /quotes endpoint, so use per-symbol quote calls.
            return await GetFinnhubQuotesAsync(symbols, cancellationToken);
        }

        if (ShouldUseYahooFallback())
        {
            return await GetFreeQuotesAsync(symbols, cancellationToken);
        }

        var key = await ResolveApiKeyAsync(cancellationToken);
        if (string.IsNullOrWhiteSpace(key) || _httpClient.BaseAddress is null)
        {
            return symbols.Select(CreateUnavailableQuote).ToArray();
        }

        var symbolQuery = string.Join(',', symbols.Select(Uri.EscapeDataString));
        var request = new HttpRequestMessage(HttpMethod.Get, $"/quotes?symbols={symbolQuery}");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);

        try
        {
            using var response = await _httpClient.SendAsync(request, cancellationToken);
            if (response.StatusCode == HttpStatusCode.TooManyRequests)
            {
                return symbols.Select(s => CreateUnavailableQuote(s) with
                {
                    ErrorCode = "THROTTLED",
                    ErrorMessage = "Provider throttled the request",
                    FreshnessStatus = FreshnessStatus.Unavailable,
                    IsLive = false
                }).ToArray();
            }

            if (!response.IsSuccessStatusCode)
            {
                return symbols.Select(CreateUnavailableQuote).ToArray();
            }

            var content = await response.Content.ReadAsStringAsync(cancellationToken);
            using var doc = JsonDocument.Parse(content);

            var records = new List<QuoteRecord>();
            if (doc.RootElement.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in doc.RootElement.EnumerateArray())
                {
                    var source = ResolveQuoteSource(item);

                    var symbol = TryGetFirstPropertyValue(source, "symbol", "ticker")
                        ?? TryGetFirstPropertyValue(item, "symbol", "ticker")
                        ?? "UNKNOWN";

                    var dataSource = TryGetFirstPropertyValue(source, "provider", "dataSource")
                        ?? TryGetFirstPropertyValue(item, "provider", "dataSource")
                        ?? ProviderName;

                    var isLiveText = TryGetFirstPropertyValue(source, "isLive", "live", "isRealtime")
                        ?? TryGetFirstPropertyValue(item, "isLive", "live", "isRealtime");
                    var isLive = bool.TryParse(isLiveText, out var parsedLive) && parsedLive;

                    var marketStatusText = TryGetFirstPropertyValue(source, "marketStatus", "status")
                        ?? TryGetFirstPropertyValue(item, "marketStatus", "status");

                    _ = Enum.TryParse<MarketStatus>(marketStatusText, true, out var marketStatus);

                    var freshnessText = TryGetFirstPropertyValue(source, "freshnessStatus", "freshness")
                        ?? TryGetFirstPropertyValue(item, "freshnessStatus", "freshness");

                    _ = Enum.TryParse<FreshnessStatus>(freshnessText, true, out var freshnessStatus);

                    var fields = new Dictionary<string, string?>();
                    foreach (var column in ColumnNames.All)
                    {
                        fields[column] = ResolveFieldValue(source, item, column);
                    }
                    fields[ColumnNames.Volume] = FormatVolumeWithSeparators(fields[ColumnNames.Volume]);

                    // Preserve provider-specific after-hours data under stable keys for clients.
                    fields["AfterHoursPrice"] = ResolveFieldValue(source, item, "AfterHoursPrice");
                    fields["AfterHoursChange"] = ResolveFieldValue(source, item, "AfterHoursChange");
                    fields["AfterHoursChangePercent"] = ResolveFieldValue(source, item, "AfterHoursChangePercent");

                    records.Add(new QuoteRecord
                    {
                        Symbol = symbol,
                        DataSource = dataSource,
                        RetrievedAtUtc = DateTimeOffset.UtcNow,
                        MarketStatus = marketStatus,
                        FreshnessStatus = freshnessStatus,
                        IsLive = isLive,
                        Fields = fields,
                        MissingFields = fields.Where(x => string.IsNullOrWhiteSpace(x.Value)).Select(x => x.Key).ToArray(),
                        CalculatedFields = Array.Empty<string>()
                    });
                }
            }

            return records.Count == 0 ? symbols.Select(CreateUnavailableQuote).ToArray() : records;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Quote retrieval failed.");
            return symbols.Select(s => CreateUnavailableQuote(s) with { ErrorMessage = ex.Message }).ToArray();
        }
    }

    private QuoteRecord CreateUnavailableQuote(string symbol)
    {
        return new QuoteRecord
        {
            Symbol = symbol,
            DataSource = ProviderName,
            RetrievedAtUtc = DateTimeOffset.UtcNow,
            MarketStatus = MarketStatus.Unavailable,
            FreshnessStatus = FreshnessStatus.Unavailable,
            IsLive = false,
            Message = "Market closed or live data unavailable.",
            Fields = ColumnNames.All.ToDictionary(c => c, _ => (string?)null),
            MissingFields = ColumnNames.All,
            ErrorCode = "UNAVAILABLE",
            ErrorMessage = "Provider unavailable or not configured"
        };
    }

    private async Task<string?> ResolveApiKeyAsync(CancellationToken cancellationToken)
    {
        var inline = _configuration["MarketDataProvider:ApiKey"];
        if (!string.IsNullOrWhiteSpace(inline))
        {
            return inline;
        }

        var secretName = _options.ApiKeySecretName;
        var keyVaultUri = _configuration["KeyVault:Uri"];

        if (string.IsNullOrWhiteSpace(secretName) || string.IsNullOrWhiteSpace(keyVaultUri))
        {
            return null;
        }

        try
        {
            var client = new SecretClient(new Uri(keyVaultUri), new DefaultAzureCredential());
            var secret = await client.GetSecretAsync(secretName, cancellationToken: cancellationToken);
            return secret.Value.Value;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Unable to resolve market data provider API key from Key Vault.");
            return null;
        }
    }

    private bool ShouldUseYahooFallback()
    {
        var providerName = _options.ProviderName?.Trim();
        var baseUrl = _options.BaseUrl?.Trim();

        if (string.IsNullOrWhiteSpace(providerName)
            || providerName.Equals("Unconfigured", StringComparison.OrdinalIgnoreCase)
            || string.IsNullOrWhiteSpace(baseUrl))
        {
            return true;
        }

        return string.Equals(_options.ProviderName, "PlaceholderProvider", StringComparison.OrdinalIgnoreCase)
            || (_options.BaseUrl?.Contains("example.invalid", StringComparison.OrdinalIgnoreCase) ?? false);
    }

    private bool ShouldUseFinnhubProvider()
    {
        if (string.Equals(_options.ProviderName, "Finnhub", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        return (_options.BaseUrl?.Contains("finnhub.io", StringComparison.OrdinalIgnoreCase) ?? false);
    }

    private async Task<IReadOnlyCollection<QuoteRecord>> GetYahooQuotesAsync(IReadOnlyCollection<string> symbols, CancellationToken cancellationToken)
    {
        var normalized = symbols.Select(s => s.Trim().ToUpperInvariant()).Where(s => !string.IsNullOrWhiteSpace(s)).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        if (normalized.Length == 0)
        {
            return Array.Empty<QuoteRecord>();
        }

        try
        {
            var symbolQuery = string.Join(',', normalized.Select(Uri.EscapeDataString));
            using var request = new HttpRequestMessage(HttpMethod.Get, $"{YahooQuoteEndpoint}?symbols={symbolQuery}");
            using var response = await _httpClient.SendAsync(request, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                return normalized.Select(CreateUnavailableQuote).ToArray();
            }

            var payload = await response.Content.ReadAsStringAsync(cancellationToken);
            using var doc = JsonDocument.Parse(payload);
            if (!doc.RootElement.TryGetProperty("quoteResponse", out var quoteResponse)
                || !quoteResponse.TryGetProperty("result", out var results)
                || results.ValueKind != JsonValueKind.Array)
            {
                return normalized.Select(CreateUnavailableQuote).ToArray();
            }

            var records = new Dictionary<string, QuoteRecord>(StringComparer.OrdinalIgnoreCase);
            foreach (var item in results.EnumerateArray())
            {
                var symbol = TryGetFirstPropertyValue(item, "symbol")?.ToUpperInvariant();
                if (string.IsNullOrWhiteSpace(symbol))
                {
                    continue;
                }

                var marketState = TryGetFirstPropertyValue(item, "marketState");
                var marketStatus = marketState?.Equals("REGULAR", StringComparison.OrdinalIgnoreCase) == true
                    ? MarketStatus.Open
                    : MarketStatus.Closed;

                var fields = new Dictionary<string, string?>();
                foreach (var column in ColumnNames.All)
                {
                    fields[column] = ResolveFieldValue(item, item, column);
                }
                fields[ColumnNames.Volume] = FormatVolumeWithSeparators(fields[ColumnNames.Volume]);

                if (string.IsNullOrWhiteSpace(fields[ColumnNames.Chg]))
                {
                    fields[ColumnNames.Chg] = TryGetFirstPropertyValue(item, "regularMarketChange");
                }

                var dayLow = TryGetFirstPropertyValue(item, "regularMarketDayLow");
                var dayHigh = TryGetFirstPropertyValue(item, "regularMarketDayHigh");
                if (!string.IsNullOrWhiteSpace(dayLow) && !string.IsNullOrWhiteSpace(dayHigh))
                {
                    fields[ColumnNames.DayRange] ??= $"{dayLow}-{dayHigh}";
                }

                var wkLow = TryGetFirstPropertyValue(item, "fiftyTwoWeekLow");
                var wkHigh = TryGetFirstPropertyValue(item, "fiftyTwoWeekHigh");
                if (!string.IsNullOrWhiteSpace(wkLow) && !string.IsNullOrWhiteSpace(wkHigh))
                {
                    fields[ColumnNames.FiftyTwoWkRange] ??= $"{wkLow}-{wkHigh}";
                }

                fields["AfterHoursPrice"] = ResolveFieldValue(item, item, "AfterHoursPrice");
                fields["AfterHoursChange"] = ResolveFieldValue(item, item, "AfterHoursChange");
                fields["AfterHoursChangePercent"] = ResolveFieldValue(item, item, "AfterHoursChangePercent");

                records[symbol] = new QuoteRecord
                {
                    Symbol = symbol,
                    DataSource = "YahooFinance",
                    RetrievedAtUtc = DateTimeOffset.UtcNow,
                    MarketStatus = marketStatus,
                    FreshnessStatus = FreshnessStatus.Live,
                    IsLive = true,
                    Fields = fields,
                    MissingFields = fields.Where(x => string.IsNullOrWhiteSpace(x.Value)).Select(x => x.Key).ToArray(),
                    CalculatedFields = Array.Empty<string>()
                };
            }

            return normalized
                .Select(symbol => records.TryGetValue(symbol, out var record) ? record : CreateUnavailableQuote(symbol))
                .ToArray();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Yahoo fallback quote retrieval failed.");
            return normalized.Select(s => CreateUnavailableQuote(s) with { ErrorMessage = ex.Message }).ToArray();
        }
    }

    private async Task<IReadOnlyCollection<QuoteRecord>> GetFreeQuotesAsync(IReadOnlyCollection<string> symbols, CancellationToken cancellationToken)
    {
        var finnhub = await GetFinnhubQuotesAsync(symbols, cancellationToken);
        var hasUsableFinnhubValues = finnhub.Any(r =>
            !string.IsNullOrWhiteSpace(GetField(r, ColumnNames.Last))
            || !string.IsNullOrWhiteSpace(GetField(r, ColumnNames.Chg))
            || !string.IsNullOrWhiteSpace(GetField(r, ColumnNames.Volume)));

        if (hasUsableFinnhubValues)
        {
            return finnhub;
        }

        var yahoo = await GetYahooQuotesAsync(symbols, cancellationToken);
        var hasUsableYahooValues = yahoo.Any(r =>
            !string.IsNullOrWhiteSpace(GetField(r, ColumnNames.Last))
            || !string.IsNullOrWhiteSpace(GetField(r, ColumnNames.Chg))
            || !string.IsNullOrWhiteSpace(GetField(r, ColumnNames.Volume)));

        if (hasUsableYahooValues)
        {
            return yahoo;
        }

        _logger.LogWarning("Yahoo fallback returned no usable Last/Chg/Volume values. Trying Stooq fallback.");
        var stooq = await GetStooqQuotesAsync(symbols, cancellationToken);
        var merged = new Dictionary<string, QuoteRecord>(StringComparer.OrdinalIgnoreCase);

        foreach (var record in finnhub)
        {
            merged[record.Symbol] = record;
        }

        foreach (var record in yahoo)
        {
            if (!merged.TryGetValue(record.Symbol, out var current))
            {
                merged[record.Symbol] = record;
                continue;
            }

            if (HasNoCoreValues(current) && !HasNoCoreValues(record))
            {
                merged[record.Symbol] = record;
            }
        }

        foreach (var record in stooq)
        {
            if (!merged.TryGetValue(record.Symbol, out var current))
            {
                merged[record.Symbol] = record;
                continue;
            }

            if (HasNoCoreValues(current) && !HasNoCoreValues(record))
            {
                merged[record.Symbol] = record;
            }
        }

        return symbols
            .Select(s => s.Trim().ToUpperInvariant())
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Select(s => merged.TryGetValue(s, out var record) ? record : CreateUnavailableQuote(s))
            .ToArray();
    }

    private async Task<IReadOnlyCollection<QuoteRecord>> GetFinnhubQuotesAsync(IReadOnlyCollection<string> symbols, CancellationToken cancellationToken)
    {
        var normalized = symbols.Select(s => s.Trim().ToUpperInvariant()).Where(s => !string.IsNullOrWhiteSpace(s)).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        if (normalized.Length == 0)
        {
            return Array.Empty<QuoteRecord>();
        }

        var apiKey = await ResolveApiKeyAsync(cancellationToken);
        if (string.IsNullOrWhiteSpace(apiKey))
        {
            _logger.LogInformation("Finnhub key not configured. Skipping Finnhub free-source pass.");
            return normalized.Select(CreateUnavailableQuote).ToArray();
        }

        var requests = normalized.Select(symbol => GetFinnhubQuoteAsync(symbol, apiKey, cancellationToken));
        var results = await Task.WhenAll(requests);
        return results;
    }

    private async Task<QuoteRecord> GetFinnhubQuoteAsync(string symbol, string apiKey, CancellationToken cancellationToken)
    {
        try
        {
            var requestUri = $"{FinnhubQuoteEndpoint}?symbol={Uri.EscapeDataString(symbol)}&token={Uri.EscapeDataString(apiKey)}";
            using var request = new HttpRequestMessage(HttpMethod.Get, requestUri);
            using var response = await _httpClient.SendAsync(request, cancellationToken);

            if (!response.IsSuccessStatusCode)
            {
                return CreateUnavailableQuote(symbol);
            }

            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
            if (json.RootElement.ValueKind != JsonValueKind.Object)
            {
                return CreateUnavailableQuote(symbol);
            }

            var current = TryGetNumberAsString(json.RootElement, "c");
            var change = TryGetNumberAsString(json.RootElement, "d");
            var changePercent = TryGetNumberAsString(json.RootElement, "dp");
            var dayHigh = TryGetNumberAsString(json.RootElement, "h");
            var dayLow = TryGetNumberAsString(json.RootElement, "l");
            var open = TryGetNumberAsString(json.RootElement, "o");
            var prevClose = TryGetNumberAsString(json.RootElement, "pc");
            var volume = await GetFinnhubMetricVolumeAsync(symbol, apiKey, cancellationToken);

            // Finnhub returns zeros for invalid or unavailable symbols on the free endpoint.
            if (string.IsNullOrWhiteSpace(current) || current == "0")
            {
                return CreateUnavailableQuote(symbol);
            }

            var fields = ColumnNames.All.ToDictionary(c => c, _ => (string?)null, StringComparer.OrdinalIgnoreCase);
            fields[ColumnNames.Symbol] = symbol;
            fields[ColumnNames.Last] = current;
            fields[ColumnNames.Chg] = !string.IsNullOrWhiteSpace(changePercent)
                ? $"{change} ({changePercent}%)"
                : change;
            fields[ColumnNames.Bid] = current;
            fields[ColumnNames.Ask] = current;
            fields[ColumnNames.PrevClose] = prevClose;
            fields[ColumnNames.DayRange] = BuildRange(dayLow, dayHigh);
            fields[ColumnNames.CloseValue] = prevClose;
            fields[ColumnNames.Volume] = volume;
            fields[ColumnNames.Volume] = FormatVolumeWithSeparators(fields[ColumnNames.Volume]);

            if (!string.IsNullOrWhiteSpace(open) && !string.IsNullOrWhiteSpace(current) && decimal.TryParse(open, out var openValue) && decimal.TryParse(current, out var currentValue) && string.IsNullOrWhiteSpace(change))
            {
                fields[ColumnNames.Chg] = (currentValue - openValue).ToString("0.####");
            }

            return new QuoteRecord
            {
                Symbol = symbol,
                DataSource = "Finnhub",
                RetrievedAtUtc = DateTimeOffset.UtcNow,
                MarketStatus = MarketStatus.Open,
                FreshnessStatus = FreshnessStatus.Live,
                IsLive = true,
                Fields = fields,
                MissingFields = fields.Where(x => string.IsNullOrWhiteSpace(x.Value)).Select(x => x.Key).ToArray(),
                CalculatedFields = Array.Empty<string>()
            };
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Finnhub fallback quote retrieval failed for {Symbol}", symbol);
            return CreateUnavailableQuote(symbol) with { ErrorMessage = ex.Message };
        }
    }

    private async Task<string?> GetFinnhubMetricVolumeAsync(string symbol, string apiKey, CancellationToken cancellationToken)
    {
        try
        {
            var requestUri = $"{FinnhubMetricEndpoint}?symbol={Uri.EscapeDataString(symbol)}&metric=all&token={Uri.EscapeDataString(apiKey)}";
            using var request = new HttpRequestMessage(HttpMethod.Get, requestUri);
            using var response = await _httpClient.SendAsync(request, cancellationToken);

            if (!response.IsSuccessStatusCode)
            {
                return null;
            }

            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
            if (json.RootElement.ValueKind != JsonValueKind.Object)
            {
                return null;
            }

            if (!json.RootElement.TryGetProperty("metric", out var metrics) || metrics.ValueKind != JsonValueKind.Object)
            {
                return null;
            }

            var tenDayAverage = TryGetNumberAsString(metrics, "10DayAverageTradingVolume");
            var threeMonthAverage = TryGetNumberAsString(metrics, "3MonthAverageTradingVolume");
            var selected = tenDayAverage ?? threeMonthAverage;

            if (string.IsNullOrWhiteSpace(selected) || !decimal.TryParse(selected, out var value) || value <= 0)
            {
                return null;
            }

            // Finnhub metric average volumes are commonly reported in millions for US equities.
            if (value < 100000m)
            {
                value *= 1_000_000m;
            }

            return decimal.Round(value, 0).ToString("0");
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Finnhub metric volume retrieval failed for {Symbol}", symbol);
            return null;
        }
    }

    private async Task<IReadOnlyCollection<QuoteRecord>> GetStooqQuotesAsync(IReadOnlyCollection<string> symbols, CancellationToken cancellationToken)
    {
        var normalized = symbols.Select(s => s.Trim().ToUpperInvariant()).Where(s => !string.IsNullOrWhiteSpace(s)).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        if (normalized.Length == 0)
        {
            return Array.Empty<QuoteRecord>();
        }

        try
        {
            var stooqSymbols = normalized.Select(ToStooqSymbol).ToArray();
            var query = string.Join(',', stooqSymbols.Select(Uri.EscapeDataString));
            using var request = new HttpRequestMessage(HttpMethod.Get, $"{StooqEndpoint}?s={query}&f=sd2t2ohlcv&h&e=csv");
            using var response = await _httpClient.SendAsync(request, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                return normalized.Select(CreateUnavailableQuote).ToArray();
            }

            var csv = await response.Content.ReadAsStringAsync(cancellationToken);
            var bySymbol = ParseStooqCsv(csv);

            return normalized.Select(symbol =>
            {
                if (!bySymbol.TryGetValue(symbol, out var row))
                {
                    return CreateUnavailableQuote(symbol);
                }

                var open = row.Open;
                var close = row.Close;
                var change = ComputeChange(open, close);
                var fields = ColumnNames.All.ToDictionary(c => c, _ => (string?)null, StringComparer.OrdinalIgnoreCase);
                fields[ColumnNames.Symbol] = symbol;
                fields[ColumnNames.Last] = close;
                fields[ColumnNames.Chg] = change;
                fields[ColumnNames.Volume] = row.Volume;
                fields[ColumnNames.Volume] = FormatVolumeWithSeparators(fields[ColumnNames.Volume]);
                fields[ColumnNames.Bid] = close;
                fields[ColumnNames.Ask] = close;
                fields[ColumnNames.DayRange] = BuildRange(row.Low, row.High);
                fields[ColumnNames.CloseValue] = close;

                return new QuoteRecord
                {
                    Symbol = symbol,
                    DataSource = "Stooq",
                    RetrievedAtUtc = DateTimeOffset.UtcNow,
                    MarketStatus = MarketStatus.Open,
                    FreshnessStatus = FreshnessStatus.Live,
                    IsLive = true,
                    Fields = fields,
                    MissingFields = fields.Where(x => string.IsNullOrWhiteSpace(x.Value)).Select(x => x.Key).ToArray(),
                    CalculatedFields = Array.Empty<string>()
                };
            }).ToArray();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Stooq fallback quote retrieval failed.");
            return normalized.Select(s => CreateUnavailableQuote(s) with { ErrorMessage = ex.Message }).ToArray();
        }
    }

    private static bool HasNoCoreValues(QuoteRecord record)
    {
        return string.IsNullOrWhiteSpace(GetField(record, ColumnNames.Last))
            && string.IsNullOrWhiteSpace(GetField(record, ColumnNames.Chg))
            && string.IsNullOrWhiteSpace(GetField(record, ColumnNames.Volume));
    }

    private static string? GetField(QuoteRecord record, string key)
    {
        return record.Fields.TryGetValue(key, out var value) ? value : null;
    }

    private static string ToStooqSymbol(string symbol)
    {
        if (symbol.EndsWith(".US", StringComparison.OrdinalIgnoreCase))
        {
            return symbol.ToLowerInvariant();
        }

        return $"{symbol.ToLowerInvariant()}.us";
    }

    private static Dictionary<string, StooqRow> ParseStooqCsv(string csv)
    {
        var result = new Dictionary<string, StooqRow>(StringComparer.OrdinalIgnoreCase);
        var lines = csv.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (lines.Length <= 1)
        {
            return result;
        }

        foreach (var line in lines.Skip(1))
        {
            var cells = line.Split(',', StringSplitOptions.None);
            // Stooq schema has 8 columns: Symbol,Date,Time,Open,High,Low,Close,Volume.
            if (cells.Length < 8)
            {
                continue;
            }

            var symbol = cells[0].Trim();
            if (string.IsNullOrWhiteSpace(symbol) || symbol.Equals("N/D", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var normalized = symbol.EndsWith(".US", StringComparison.OrdinalIgnoreCase)
                ? symbol[..^3].ToUpperInvariant()
                : symbol.ToUpperInvariant();

            result[normalized] = new StooqRow(
                Open: SanitizeStooqValue(cells.ElementAtOrDefault(3)),
                High: SanitizeStooqValue(cells.ElementAtOrDefault(4)),
                Low: SanitizeStooqValue(cells.ElementAtOrDefault(5)),
                Close: SanitizeStooqValue(cells.ElementAtOrDefault(6)),
                Volume: SanitizeStooqValue(cells.ElementAtOrDefault(7)));
        }

        return result;
    }

    private static string? SanitizeStooqValue(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var trimmed = value.Trim();
        return trimmed.Equals("N/D", StringComparison.OrdinalIgnoreCase) ? null : trimmed;
    }

    private static string? BuildRange(string? low, string? high)
    {
        if (string.IsNullOrWhiteSpace(low) || string.IsNullOrWhiteSpace(high))
        {
            return null;
        }

        return $"{low}-{high}";
    }

    private static string? ComputeChange(string? open, string? close)
    {
        if (!decimal.TryParse(open, out var openValue) || !decimal.TryParse(close, out var closeValue))
        {
            return null;
        }

        return (closeValue - openValue).ToString("0.####");
    }

    private static string? FormatVolumeWithSeparators(string? rawVolume)
    {
        if (string.IsNullOrWhiteSpace(rawVolume))
        {
            return rawVolume;
        }

        var normalized = rawVolume.Replace(",", string.Empty, StringComparison.Ordinal).Trim();

        if (long.TryParse(normalized, NumberStyles.Integer, CultureInfo.InvariantCulture, out var longValue))
        {
            return longValue.ToString("N0", CultureInfo.InvariantCulture);
        }

        if (decimal.TryParse(normalized, NumberStyles.Number, CultureInfo.InvariantCulture, out var decimalValue))
        {
            return decimal.Round(decimalValue, 0).ToString("N0", CultureInfo.InvariantCulture);
        }

        return rawVolume;
    }

    private static string? TryGetNumberAsString(JsonElement item, string propertyName)
    {
        if (!item.TryGetProperty(propertyName, out var value))
        {
            return null;
        }

        if (value.ValueKind == JsonValueKind.Number)
        {
            if (value.TryGetDecimal(out var dec))
            {
                return dec.ToString("0.####");
            }

            return value.ToString();
        }

        if (value.ValueKind == JsonValueKind.String)
        {
            var text = value.GetString();
            return string.IsNullOrWhiteSpace(text) ? null : text;
        }

        return null;
    }

    private sealed record StooqRow(string? Open, string? High, string? Low, string? Close, string? Volume);

    private static string? TryGetFirstPropertyValue(JsonElement item, params string[] propertyNames)
    {
        if (item.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        var properties = item.EnumerateObject()
            .ToDictionary(p => p.Name, p => p.Value, StringComparer.OrdinalIgnoreCase);

        foreach (var propertyName in propertyNames)
        {
            if (properties.TryGetValue(propertyName, out var propertyValue))
            {
                var text = propertyValue.ToString();
                if (!string.IsNullOrWhiteSpace(text))
                {
                    return text;
                }
            }
        }

        return null;
    }

    private static JsonElement ResolveQuoteSource(JsonElement item)
    {
        if (item.ValueKind == JsonValueKind.Object && item.TryGetProperty("quote", out var nestedQuote) && nestedQuote.ValueKind == JsonValueKind.Object)
        {
            return nestedQuote;
        }

        return item;
    }

    private static string? ResolveFieldValue(JsonElement source, JsonElement fallback, string canonicalField)
    {
        var direct = TryGetFirstPropertyValue(source, canonicalField)
            ?? TryGetFirstPropertyValue(fallback, canonicalField);
        if (!string.IsNullOrWhiteSpace(direct))
        {
            return direct;
        }

        if (!FieldAliases.TryGetValue(canonicalField, out var aliases) || aliases.Length == 0)
        {
            return null;
        }

        return TryGetFirstPropertyValue(source, aliases)
            ?? TryGetFirstPropertyValue(fallback, aliases);
    }
}
