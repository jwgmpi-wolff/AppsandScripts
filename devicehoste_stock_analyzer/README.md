# Stock Movement Analyzer

A native Android app that produces explainable, probabilistic short-horizon stock movement analysis from live or recently retrieved intraday market data. It does not provide financial advice, recommendations, or guaranteed outcomes.

## Architecture

- `app/`: Kotlin, Jetpack Compose, StateFlow/ViewModel, OkHttp, and Kotlin Serialization.
- `domain/`: indicators, news freshness validation, non-hallucination validation, and weighted scoring independent of UI.
- `data/`: keyless Yahoo Finance adapter, optional HTTPS proxy adapter, provider errors, and repository orchestration.
- `ui/`: persistent watchlist, locally logged holdings, adaptive stock-card grid, 10-60 minute and 1/5/10-day horizon controls, live refresh, and evidence detail view.
- `proxy/`: optional dependency-free Node 20 adapter for deployments that already use Finnhub or Alpha Vantage.

The default Android client calls Yahoo Finance public HTTPS endpoints directly. It requires no account, API key, paid plan, proxy deployment, or `marketData.baseUrl` setting. Yahoo Finance is a best-effort public source rather than a contracted application API, so availability, throttling, and response compatibility are not guaranteed. Provider failures never generate substituted market values.

## Live data provider

The app retrieves:

- current price and source timestamp from `query1.finance.yahoo.com/v8/finance/chart`;
- one-minute OHLCV for intraday horizons and daily OHLCV for 1/5/10-day horizons from the same chart endpoint;
- ticker-related headlines, publishers, links, and publication timestamps from `query1.finance.yahoo.com/v1/finance/search`.

Quote and candle calls share a short in-memory response cache to avoid duplicate chart requests during one refresh. Responses are not persisted by the app. News is filtered to articles whose `relatedTickers` contains the requested symbol. Yahoo does not provide a sentiment score in this response, so the app labels its deterministic local headline score rather than attributing that score to Yahoo.

No setup is required beyond normal Android internet access. `local.properties` remains optional and may contain only Android SDK settings or non-secret analyzer thresholds.

## Optional proxy adapter

The repository retains `proxy/` for users who separately choose Finnhub or Alpha Vantage. It is not used by the default app and is not required for free updates.

For local proxy development in PowerShell:

```powershell
Set-Location .\proxy
$env:MARKET_DATA_PROVIDER = 'finnhub'
$env:FINNHUB_API_KEY = '<enter in your terminal; never commit it>'
npm start
```

The optional proxy adapter requires HTTPS. Never put an API key in `local.properties` or the APK.

### Proxy contract

The app calls:

- `GET /v1/quote/{symbol}`
- `GET /v1/candles/{symbol}?interval=1&range=120` for one-minute candles
- `GET /v1/candles/{symbol}?interval=1440&range=129600` for daily candles
- `GET /v1/news/{symbol}` for newly retrieved, timestamped company news

Quote responses contain `symbol`, numeric `price`, ISO-8601 `timestamp`, and `provider`. Candle responses contain `provider`, ISO-8601 `retrievedAt`, `intervalMinutes`, and timestamped OHLC candles with optional volume. News responses contain the provider and retrieval time plus each article's headline, source, publication time, URL, score, and scoring method. Provider errors map to `404` unsupported symbol, `423` market closed/no recent data, `429` rate limit, and `5xx` provider unavailable. Responses use `Cache-Control: no-store`.

## Run Android

Prerequisites: Android Studio or JDK 17, Android SDK 35, and an Android 8.0 (API 26) or newer emulator/device.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

If Yahoo Finance is unreachable, throttles the device, changes its response contract, or returns stale/insufficient data, the app displays **Live data unavailable** or `NEUTRAL / INSUFFICIENT DATA` and generates no directional prediction.

## Watchlist and holdings

Entering a valid symbol and tapping the add icon saves it to the device watchlist. Use the pencil icon on a stock card to log positive whole or fractional shares and an optional nonnegative average cost. Use the trash icon to remove one symbol, or the clear-watchlist icon in the top bar to remove all symbols. Destructive actions require confirmation.

Watchlist and holding records are stored only in Android DataStore for this app. They are not uploaded to the market-data proxy and do not affect prediction scoring. A live holding value is displayed only when a real validated quote is available; otherwise no value is estimated. Reinstalling with `adb install -r` preserves this data, while uninstalling or clearing app storage removes it.

## Prediction model

For the selected 10, 20, 30, 40, 50, or 60 minute horizon, the analyzer uses one-minute candles. The 1, 5, and 10-day projections use provider daily candles. In both cases, only supported values calculated from retrieved candles are used:

| Signal | Default weight | Calculation |
| --- | ---: | --- |
| Momentum | 0.30 | Price change across the selected horizon, capped before weighting |
| Trend | 0.20 | Five-period SMA compared with 12-period SMA |
| Volume | 0.10 | Latest volume versus its recent average, with price relative to VWAP |
| RSI | 0.15 | Fourteen-period RSI |
| MACD | 0.15 | Difference between 12- and 26-period EMAs |
| News sentiment | 0.10 | Average of fresh, sourced, ticker-specific article scores |

Unavailable indicators are excluded rather than substituted. At least 60% of signal weight must be supported. The score is normalized by available weight. A score at or above `0.2` is `UP`; at or below `-0.2` is `DOWN`; otherwise it is `NEUTRAL / INSUFFICIENT DATA`. Confidence is the absolute normalized score times 100, capped to 0-100. Thresholds can be changed through the non-secret `local.properties` values.

Each refresh independently requests quote, candle, and news data. Intraday projections use articles published within 24 hours; daily projections use articles published within seven days. Stale, future-dated, source-less, headline-less, ticker-unrelated, or provider-mismatched items are excluded. Yahoo Finance does not return sentiment in the public search response, so the app applies a small deterministic positive/negative headline lexicon and labels every score `Deterministic headline lexicon`; it does not claim those scores came from Yahoo.

## Non-hallucination checks

Before a directional result is allowed, the app verifies:

- provider identity and a timestamped quote are present;
- quote and candle timestamps are not future-dated or older than 15 minutes for intraday analysis or five calendar days for daily analysis;
- quote and candle provider identities match;
- enough candles exist for the selected horizon;
- candle interval and numeric values are valid;
- at least 60% of weighted signals can be calculated from retrieved values.

If any required check fails, confidence is zero, direction is `NEUTRAL / INSUFFICIENT DATA`, and the reason is displayed. Provider/network/configuration failures do not create an `AnalysisResult`; the UI displays **Live data unavailable** and `Not calculated` instead. The detail view exposes provider, pull timestamp, latest source timestamp, age, candle interval, quote timestamp, indicator values, each signal weight/contribution, missing inputs, confidence calculation, and final reason.

Run both test suites to verify these behaviors:

```powershell
.\gradlew.bat testDebugUnitTest
Push-Location .\proxy
npm test
Pop-Location
```

## Troubleshooting

- **Rate limit exceeded:** wait for Yahoo Finance to accept requests again; the app does not bypass provider throttling.
- **No internet/provider unavailable:** verify Android connectivity and Yahoo Finance availability.
- **Market closed/stale data:** no directional result is generated after the 15-minute freshness limit.
- **Unsupported symbol:** use an exchange symbol accepted by the configured provider.
- **Insufficient candles:** wait for enough current one-minute candles or choose a shorter horizon.

## Known limitations

- This is a technical-signal model, not a trained predictive AI model, and it cannot account for future events.
- Yahoo Finance public endpoints are free and keyless but are unofficial for this application use and can change, throttle, delay, or become unavailable without notice.
- Day projections use trading-session candles, so 5-day and 10-day labels refer to five and ten observed sessions rather than guaranteed calendar-day outcomes.
- Alpha Vantage compact intraday output and Finnhub candle availability may limit coverage.
- Headline sentiment is a limited contextual signal and does not understand sarcasm, nuance, article bodies, or future events.
- Live refresh is enabled by default every 60 seconds while this app screen and ViewModel remain active; there is no background worker.