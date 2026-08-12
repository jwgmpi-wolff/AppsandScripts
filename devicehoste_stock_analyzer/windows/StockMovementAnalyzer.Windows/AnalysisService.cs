using System.Net.Http;
using System.Text.Json;

namespace StockMovementAnalyzer.Windows;

public sealed class AnalysisService(HttpClient httpClient)
{
    private const string Provider = "Yahoo Finance";

    public async Task<AnalysisResult> AnalyzeAsync(string symbol, HorizonDefinition horizon, CancellationToken cancellationToken)
    {
        var normalized = symbol.Trim().ToUpperInvariant();
        var quoteRequest = GetQuoteAsync(normalized, cancellationToken);
        var candlesRequest = GetCandlesAsync(normalized, horizon, cancellationToken);
        var newsRequest = GetNewsAsync(normalized, cancellationToken);
        await Task.WhenAll(quoteRequest, candlesRequest);
        var quote = await quoteRequest;
        var candles = await candlesRequest;
        NewsSentimentBatch? news = null;
        string? newsWarning = null;
        try { news = await newsRequest; }
        catch (Exception error) { newsWarning = error.Message; }
        var snapshot = new MarketSnapshot(normalized, Provider, DateTimeOffset.UtcNow, horizon.CandleIntervalMinutes, quote, candles, news, newsWarning);
        return new StockAnalyzerEngine().Analyze(snapshot, horizon);
    }

    private async Task<Quote> GetQuoteAsync(string symbol, CancellationToken cancellationToken)
    {
        using var chart = await GetJsonAsync($"/v8/finance/chart/{Uri.EscapeDataString(symbol)}?interval=1m&range=1d", cancellationToken);
        var result = GetChartResult(chart);
        var meta = result.GetProperty("meta");
        if (!meta.TryGetProperty("regularMarketPrice", out var priceValue) || priceValue.ValueKind != JsonValueKind.Number)
            throw new InvalidOperationException("Current price was unavailable.");
        if (!meta.TryGetProperty("regularMarketTime", out var timestampValue) || !timestampValue.TryGetInt64(out var timestamp))
            throw new InvalidOperationException("Quote timestamp was unavailable.");
        var regularPrice = priceValue.GetDouble();
        var preMarketPrice = OptionalDouble(meta, "preMarketPrice");
        var afterHoursPrice = OptionalDouble(meta, "postMarketPrice");
        var preMarketChangePercent = OptionalDouble(meta, "preMarketChangePercent")
            ?? (preMarketPrice is double prePrice && regularPrice != 0.0 ? ((prePrice - regularPrice) / regularPrice) * 100.0 : null);
        var afterHoursChangePercent = OptionalDouble(meta, "postMarketChangePercent")
            ?? (afterHoursPrice is double postPrice && regularPrice != 0.0 ? ((postPrice - regularPrice) / regularPrice) * 100.0 : null);
        return new Quote(
            symbol,
            regularPrice,
            DateTimeOffset.FromUnixTimeSeconds(timestamp),
            Provider,
            preMarketPrice,
            preMarketChangePercent,
            afterHoursPrice,
            afterHoursChangePercent);
    }

    private async Task<IReadOnlyList<Candle>> GetCandlesAsync(string symbol, HorizonDefinition horizon, CancellationToken cancellationToken)
    {
        var interval = horizon.IsDaily ? "1d" : "1m";
        var range = horizon.IsDaily ? "6mo" : "1d";
        using var chart = await GetJsonAsync($"/v8/finance/chart/{Uri.EscapeDataString(symbol)}?interval={interval}&range={range}", cancellationToken);
        var result = GetChartResult(chart);
        var timestamps = result.GetProperty("timestamp").EnumerateArray().Select(value => value.GetInt64()).ToList();
        var values = result.GetProperty("indicators").GetProperty("quote")[0];
        var opens = values.GetProperty("open").EnumerateArray().Select(NullableDouble).ToList();
        var highs = values.GetProperty("high").EnumerateArray().Select(NullableDouble).ToList();
        var lows = values.GetProperty("low").EnumerateArray().Select(NullableDouble).ToList();
        var closes = values.GetProperty("close").EnumerateArray().Select(NullableDouble).ToList();
        var volumes = values.GetProperty("volume").EnumerateArray().Select(NullableLong).ToList();
        var candles = new List<Candle>();
        for (var index = 0; index < timestamps.Count; index++)
        {
            if (ElementAt(opens, index) is not double open || ElementAt(highs, index) is not double high ||
                ElementAt(lows, index) is not double low || ElementAt(closes, index) is not double close) continue;
            candles.Add(new Candle(DateTimeOffset.FromUnixTimeSeconds(timestamps[index]), open, high, low, close, ElementAt(volumes, index)));
        }
        if (candles.Count == 0) throw new InvalidOperationException("Candle values were unavailable.");
        return candles;
    }

    private async Task<NewsSentimentBatch> GetNewsAsync(string symbol, CancellationToken cancellationToken)
    {
        using var search = await GetJsonAsync($"/v1/finance/search?q={Uri.EscapeDataString(symbol)}&quotesCount=0&newsCount=30", cancellationToken);
        var items = new List<TimestampedSentiment>();
        foreach (var article in search.RootElement.GetProperty("news").EnumerateArray())
        {
            if (!article.TryGetProperty("relatedTickers", out var related) ||
                !related.EnumerateArray().Any(value => string.Equals(value.GetString(), symbol, StringComparison.OrdinalIgnoreCase))) continue;
            if (!article.TryGetProperty("providerPublishTime", out var timeValue) || !timeValue.TryGetInt64(out var timestamp)) continue;
            var headline = article.TryGetProperty("title", out var titleValue) ? titleValue.GetString() : null;
            var source = article.TryGetProperty("publisher", out var publisherValue) ? publisherValue.GetString() : null;
            if (string.IsNullOrWhiteSpace(headline) || string.IsNullOrWhiteSpace(source)) continue;
            var link = article.TryGetProperty("link", out var linkValue) ? linkValue.GetString() : null;
            if (link is not null && !link.StartsWith("https://", StringComparison.Ordinal)) link = null;
            items.Add(new TimestampedSentiment(HeadlineSentimentScorer.Score(headline), source,
                DateTimeOffset.FromUnixTimeSeconds(timestamp), headline, link, "Deterministic headline lexicon"));
        }
        return new NewsSentimentBatch(Provider, DateTimeOffset.UtcNow, items);
    }

    private async Task<JsonDocument> GetJsonAsync(string path, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, $"https://query1.finance.yahoo.com{path}");
        request.Headers.Accept.ParseAdd("application/json");
        request.Headers.UserAgent.ParseAdd("StockMovementAnalyzer/1.6");
        using var response = await httpClient.SendAsync(request, cancellationToken);
        response.EnsureSuccessStatusCode();
        return JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
    }

    private static JsonElement GetChartResult(JsonDocument chart)
    {
        var results = chart.RootElement.GetProperty("chart").GetProperty("result");
        if (results.ValueKind != JsonValueKind.Array || results.GetArrayLength() == 0)
            throw new InvalidOperationException("Unsupported symbol.");
        return results[0];
    }

    private static double? OptionalDouble(JsonElement parent, string propertyName)
    {
        return parent.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.Number
            ? value.GetDouble()
            : null;
    }

    private static double? NullableDouble(JsonElement value) => value.ValueKind == JsonValueKind.Number ? value.GetDouble() : null;
    private static long? NullableLong(JsonElement value) => value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var result) ? result : null;
    private static T? ElementAt<T>(IReadOnlyList<T?> values, int index) where T : struct => index < values.Count ? values[index] : null;
}
