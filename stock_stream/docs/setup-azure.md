# Azure Setup

## Prerequisites

- Azure subscription in target tenant.
- Azure CLI installed and logged in.
- .NET 8 SDK.
- Android SDK + JDK 17.

## Deploy Infrastructure

1. Update placeholders in `infra/bicep/main.parameters.example.json`.
2. Run:

```powershell
pwsh ./infra/scripts/deploy.ps1 \
  -SubscriptionId "<subscription-guid>" \
  -ResourceGroup "rg-stockstreamportfolio" \
  -Location "eastus" \
  -AppName "stockstreamportfolio" \
  -TenantId "<tenant-guid>" \
  -BackendApiClientId "<backend-api-client-id>" \
  -BackendApiAudience "api://<backend-api-client-id>" \
  -MarketProviderName "<provider-name>" \
  -MarketProviderBaseUrl "https://<provider-host>/" \
  -MarketProviderApiKeySecretValue "<provider-api-key>"
```

## Post-Deploy Checks

- Verify backend app setting `KeyVault__Uri` is present.
- Verify managed identity has `Key Vault Secrets User` on the vault.
- Verify `GET /health` returns 200.
- Verify protected routes return 401 without token.
