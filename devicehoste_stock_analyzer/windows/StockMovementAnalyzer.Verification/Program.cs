using StockMovementAnalyzer.Windows;

var now = DateTimeOffset.Parse("2026-08-11T15:00:00Z");
var horizon = HorizonDefinition.All.Single(item => item.Id == "TEN");
var engine = new StockAnalyzerEngine();

var rising = engine.Analyze(Snapshot(now.AddMinutes(-1), index => 100.0 + index), horizon, now);
Require(rising.Direction == Direction.Up && rising.Recommendation == Recommendation.Buy, "Rising fixture must be BUY/UP.");
Require(rising.ProjectedPriceRange is not null && rising.ProjectedPriceRange.Low < rising.Quote!.Price && rising.ProjectedPriceRange.High > rising.Quote.Price,
    "Rising fixture must have a range around the quote.");

var falling = engine.Analyze(Snapshot(now.AddMinutes(-1), index => 130.0 - index), horizon, now);
Require(falling.Direction == Direction.Down && falling.Recommendation == Recommendation.Sell, "Falling fixture must be SELL/DOWN.");

var flat = engine.Analyze(Snapshot(now.AddMinutes(-1), _ => 100.0), horizon, now);
Require(flat.Direction == Direction.Neutral && flat.Recommendation == Recommendation.Hold, "Flat fixture must be HOLD/NEUTRAL.");

var stale = engine.Analyze(Snapshot(now.AddHours(-1), index => 100.0 + index), horizon, now);
Require(stale.Direction == Direction.NeutralInsufficientData && stale.Recommendation == Recommendation.Unavailable && stale.ProjectedPriceRange is null,
    "Stale fixture must fail closed.");

var newsSnapshot = Snapshot(now.AddMinutes(-1), index => 100.0 + index) with
{
    News = new NewsSentimentBatch("Mock provider (test only)", now,
    [
        new(0.8, "Current source", now.AddMinutes(-10), "Current headline", null, "Test"),
        new(-1.0, "Old source", now.AddHours(-25), "Stale headline", null, "Test"),
        new(1.0, "Future source", now.AddMinutes(1), "Future headline", null, "Test"),
    ])
};
var currentNews = engine.Analyze(newsSnapshot, horizon, now);
Require(currentNews.News?.Items.Count == 1 && currentNews.News.Items[0].Headline == "Current headline" &&
        Math.Abs((currentNews.Indicators?.SentimentAverage ?? 0.0) - 0.8) < 0.0001,
    "Only news current at analysis time may remain in the result or affect sentiment.");

var temporaryRoot = Path.Combine(Path.GetTempPath(), $"stock-analyzer-verification-{Guid.NewGuid():N}");
Directory.CreateDirectory(temporaryRoot);
try
{
    var settingsPath = Path.Combine(temporaryRoot, "settings.json");
    File.WriteAllText(settingsPath, """
        {
          "OllamaEndpoint": "http://192.168.1.10:11434",
          "UseLocalModel": true,
          "Rows": [
            { "Symbol": "MSFT", "Quantity": 12.5, "AverageCost": 310.75, "FutureField": "ignored" },
            { "Symbol": "NVDA", "Quantity": null, "AverageCost": null }
          ],
          "FutureSetting": 42
        }
        """);
    var store = new AppStore(settingsPath);
    var upgraded = store.Load();
    Require(upgraded.Rows.Count == 2 && upgraded.Rows[0] == new SavedRow("MSFT", 12.5m, 310.75m),
        "Previous-version Windows watchlist and holding must load unchanged.");
    store.Save(upgraded with { Model = "qwen3:4b" });
    File.WriteAllText(settingsPath, "corrupt update residue");
    var recovered = store.Load();
    Require(recovered.Rows.Count == 2 && recovered.Rows[0].Symbol == "MSFT",
        "Watchlist must recover from the pre-update backup.");
}
finally
{
    Directory.Delete(temporaryRoot, true);
}

Console.WriteLine("Windows parity and update-persistence verification passed.");

static MarketSnapshot Snapshot(DateTimeOffset latest, Func<int, double> closeAt)
{
    var candles = Enumerable.Range(0, 30).Select(index =>
    {
        var close = closeAt(index);
        return new Candle(latest.AddMinutes(index - 29), close - 0.2, close + 0.4, close - 0.4, close, 1_000L + index * 20);
    }).ToList();
    return new MarketSnapshot("MSFT", "Mock provider (test only)", latest, 1,
        new Quote("MSFT", candles[^1].Close, latest, "Mock provider (test only)"), candles);
}

static void Require(bool condition, string message)
{
    if (!condition) throw new InvalidOperationException(message);
}
