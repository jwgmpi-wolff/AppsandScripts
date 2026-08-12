# Stock Movement Analyzer

A native Android app that produces explainable, probabilistic short-horizon stock movement analysis from live or recently retrieved intraday market data. It does not provide financial advice, recommendations, or guaranteed outcomes.

## Architecture

- `app/`: Kotlin, Jetpack Compose, StateFlow/ViewModel, OkHttp, and Kotlin Serialization.
- `domain/`: indicators, freshness validation, non-hallucination validation, and weighted scoring independent of UI.
- `data/`: `MarketDataProvider`, HTTPS proxy adapter, provider errors, and repository orchestration.
- `ui/`: adaptive stock-card grid, 10-60 minute and 1/5/10-day horizon controls, live refresh, and evidence detail view.
- `proxy/`: dependency-free Node 20 service that keeps Finnhub or Alpha Vantage API keys off the Android device and normalizes provider responses.

The Android client contains no provider API key. Putting a key in `BuildConfig`, resources, native code, or an APK is not secure because it can be extracted. The proxy must inject keys from server-side environment variables.

## Configure live data

1. Deploy `proxy/` behind HTTPS on a trusted server.
2. Set `MARKET_DATA_PROVIDER` to `finnhub` or `alphavantage`.
3. Set only the corresponding server secret: `FINNHUB_API_KEY` or `ALPHA_VANTAGE_API_KEY`.
4. Copy `local.properties.example` to the untracked `local.properties` file.
5. Set `marketData.baseUrl` to the proxy's HTTPS origin, without a trailing API key or secret.

For local proxy development in PowerShell:

```powershell
Set-Location .\proxy
$env:MARKET_DATA_PROVIDER = 'finnhub'
$env:FINNHUB_API_KEY = '<enter in your terminal; never commit it>'
npm start
```

The Android adapter requires HTTPS. Use a trusted development tunnel or local TLS endpoint when testing from a device. Never put an API key in `local.properties`; this file only holds the non-secret proxy URL and optional scoring thresholds.

### Proxy contract

The app calls:

- `GET /v1/quote/{symbol}`
- `GET /v1/candles/{symbol}?interval=1&range=120` for one-minute candles
- `GET /v1/candles/{symbol}?interval=1440&range=129600` for daily candles

Quote responses contain `symbol`, numeric `price`, ISO-8601 `timestamp`, and `provider`. Candle responses contain `provider`, ISO-8601 `retrievedAt`, `intervalMinutes`, and timestamped OHLC candles with optional volume. Provider errors map to `404` unsupported symbol, `423` market closed/no recent data, `429` rate limit, and `5xx` provider unavailable. Responses use `Cache-Control: no-store`.

## Run Android

Prerequisites: Android Studio or JDK 17, Android SDK 35, and an Android 8.0 (API 26) or newer emulator/device.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Without a configured reachable proxy, the app intentionally displays **Live data unavailable** and generates no prediction.

## Prediction model

For the selected 10, 20, 30, 40, 50, or 60 minute horizon, the analyzer uses one-minute candles. The 1, 5, and 10-day projections use provider daily candles. In both cases, only supported values calculated from retrieved candles are used:

| Signal | Default weight | Calculation |
| --- | ---: | --- |
| Momentum | 0.35 | Price change across the selected horizon, capped before weighting |
| Trend | 0.25 | Five-period SMA compared with 12-period SMA |
| Volume | 0.10 | Latest volume versus its recent average, with price relative to VWAP |
| RSI | 0.15 | Fourteen-period RSI |
| MACD | 0.15 | Difference between 12- and 26-period EMAs |

Unavailable indicators are excluded rather than substituted. At least 60% of signal weight must be supported. The score is normalized by available weight. A score at or above `0.2` is `UP`; at or below `-0.2` is `DOWN`; otherwise it is `NEUTRAL / INSUFFICIENT DATA`. Confidence is the absolute normalized score times 100, capped to 0-100. Thresholds can be changed through the non-secret `local.properties` values.

Sentiment is not currently requested or scored. It is explicitly shown as unsupported. It must not be added unless the provider supplies timestamped source data.

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

- **API key missing:** configure it on the proxy host, never in Android.
- **Rate limit exceeded:** wait for the provider window or use an appropriately provisioned provider account.
- **No internet/provider unavailable:** verify Android connectivity, proxy HTTPS/TLS, and provider health.
- **Market closed/stale data:** no directional result is generated after the 15-minute freshness limit.
- **Unsupported symbol:** use an exchange symbol accepted by the configured provider.
- **Insufficient candles:** wait for enough current one-minute candles or choose a shorter horizon.

## Known limitations

- This is a technical-signal model, not a trained predictive AI model, and it cannot account for future events.
- Market data entitlements, delay, coverage, and rate limits depend on the configured provider plan.
- Day projections use trading-session candles, so 5-day and 10-day labels refer to five and ten observed sessions rather than guaranteed calendar-day outcomes.
- Alpha Vantage compact intraday output and Finnhub candle availability may limit coverage.
- News and sentiment are intentionally unsupported until timestamped sources are integrated.
- Live refresh is enabled by default every 60 seconds while this app screen and ViewModel remain active; there is no background worker.