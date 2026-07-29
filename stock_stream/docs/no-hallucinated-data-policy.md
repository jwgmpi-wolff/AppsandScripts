# No Hallucinated Data Policy

StockStreamPortfolio must not present inferred, fabricated, stale-labeled-as-live, or simulated market data as live.

## Enforced Requirements

1. Production startup validation
- Backend production startup fails if provider endpoint or key secret configuration is missing.

2. Quote payload integrity
- Every quote row includes:
  - `dataSource`
  - `retrievedAtUtc`
  - `marketStatus`
  - `freshnessStatus`
  - `isLive`

3. Live status rules
- `isLive=true` only when provider indicates current/live quote during market-open conditions.
- Guard logic forces `isLive=false` if `marketStatus != Open` or `freshnessStatus != Live`.

4. Missing field handling
- Missing provider fields are null/unavailable.
- No synthetic substitutions.

5. CSV data isolation
- Imported CSV values are baseline/history only.
- Imported values are never treated as live quotes.

6. Market closed handling
- UI and API display `Market closed or live data unavailable.`

7. Test coverage
- Unit tests ensure no fake value generation behavior.
- Integration tests validate auth-required and unavailable behavior paths.
