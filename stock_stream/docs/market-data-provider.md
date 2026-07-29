# Market Data Provider Integration

## Provider Abstraction

Backend interface: `IMarketDataProvider`

Required capabilities:
- real-time quote support
- delayed quote detection
- market status
- exchange/trading-hours awareness
- per-symbol field availability

## Rules

- Runtime code must never produce fake market values.
- If provider field is absent, return null/unavailable.
- If provider is delayed or stale, mark freshness accordingly and set `isLive=false`.
- If outside trading hours, return message: `Market closed or live data unavailable.`

## Provider Credentials

- Store API key in Azure Key Vault secret: `market-provider-api-key`.
- Reference secret name in backend setting `MarketDataProvider__ApiKeySecretName`.
- Never return provider keys to Android client.

## Unsupported Symbols

- Validate each symbol independently.
- Return per-symbol error states: invalid, unsupported, halted, throttled, unavailable.
- Support symbols imported from brokerage CSV where possible.
