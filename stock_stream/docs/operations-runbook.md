# Operations Runbook

## Daily Checks

- Backend health endpoint
- App Insights error rate and auth failures
- Provider latency and throttling frequency
- SignalR connection health

## Alerts to Configure

- 5xx spike on API
- Unauthorized spike (possible token/audience issue)
- Provider throttling above threshold
- Missing Key Vault secret access failures

## Incident Response

1. Confirm Entra token validation settings and audience.
2. Confirm Key Vault secret availability.
3. Confirm provider API status and quota.
4. If provider degraded, system must return unavailable/stale states, not synthetic values.

## Logging Restrictions

Do not log:
- API keys
- access tokens
- account numbers
- full portfolio rows
- sensitive account names

## KQL Examples

```kusto
requests
| where timestamp > ago(24h)
| summarize count(), avg(duration) by resultCode, operation_Name
```

```kusto
traces
| where timestamp > ago(24h)
| where message contains "THROTTLED" or message contains "Unauthorized"
| project timestamp, severityLevel, message
```
