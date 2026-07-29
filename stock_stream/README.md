# StockStreamPortfolio

StockStreamPortfolio is a secure Android + Azure solution for authenticated stock watchlists and portfolio views with live market data safeguards.

## Backend Choice

This implementation uses **ASP.NET Core on Azure App Service + Azure SignalR Service**.

Why this choice:
- Strong fit for mobile REST APIs with predictable auth middleware and OpenAPI.
- Built-in compatibility for SignalR websocket push updates.
- Easier role authorization and operational controls than event-trigger-first serverless flow for this scenario.

## Primary User

- `admin@wolffentp.net` should be granted `Admin` app role in Microsoft Entra ID.

## Repository Structure

- `android-app/` Kotlin + Jetpack Compose + MSAL mobile app
- `backend/` ASP.NET Core API with JWT auth, RBAC, provider abstraction, and OpenAPI
- `infra/` Azure Bicep + deployment scripts
- `.github/workflows/` CI/CD workflow
- `docs/` architecture, setup, auth, provider, no-hallucination policy, runbook

## Implementation Plan

- See `docs/implementation-plan.md`.

## Security and Data Integrity

- Sign-in required before Android users can view watchlist/quotes.
- Backend rejects unauthenticated requests.
- JWT bearer tokens are validated against Entra configuration.
- Provider keys are stored in Key Vault.
- Runtime does not fabricate quote values.
- If market is closed/unavailable, UI and API show `Market closed or live data unavailable.`

## Quick Start

## 1) Configure Entra App Registrations

Follow `docs/setup-entra.md`:
- Create backend API app registration.
- Create Android native app registration.
- Define and assign `Admin` and `User` roles.
- Invite/authorize guest users.

## 2) Configure Local Placeholders

### Android

1. Copy `android-app/local.properties.template` to `android-app/local.properties`.
2. Replace:
- `STOCKSTREAM_BACKEND_BASE_URL`
- `STOCKSTREAM_ANDROID_CLIENT_ID`
- `STOCKSTREAM_TENANT_ID`
- `STOCKSTREAM_BACKEND_SCOPE`
3. Update `android-app/app/src/main/res/raw/auth_config_single_account.json` placeholders.
4. Update Android redirect URI/signature hash in `android-app/app/src/main/AndroidManifest.xml`.

### Backend

1. Update `backend/src/StockStreamPortfolio.Api/appsettings.json` placeholders:
- `AzureAd:TenantId`
- `AzureAd:ClientId`
- `AzureAd:Audience`
2. Set Key Vault URI and provider settings in app config.

## 3) Deploy Azure Infrastructure and Backend

Use `infra/scripts/deploy.ps1`:

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

## 4) Run Backend Locally

```powershell
cd backend/src/StockStreamPortfolio.Api
dotnet run
```

Swagger: `https://localhost:7144/swagger`

## 5) Build Android App

```powershell
cd android-app
gradle assembleDebug
```

## Required Endpoints

Implemented API endpoints:
- `GET /health`
- `GET /me`
- `GET /settings`
- `PUT /settings`
- `GET /watchlist`
- `POST /watchlist`
- `DELETE /watchlist/{symbol}`
- `POST /watchlist/validate`
- `GET /quotes?symbols=...`
- `GET /market-status`
- `GET /columns`
- `PUT /columns/layout`
- `GET /views`
- `POST /views`
- `PUT /views/{id}`
- `DELETE /views/{id}`
- `POST /csv/validate`
- `POST /csv/import`

OpenAPI spec: `backend/openapi.yaml`

## Testing

### Backend

```powershell
cd backend
dotnet test
```

### Android JVM tests

```powershell
cd android-app
gradle test
```

## CI/CD

GitHub Actions workflow: `.github/workflows/ci-cd.yml`

Required GitHub secrets:
- `AZURE_CLIENT_ID`
- `AZURE_TENANT_ID`
- `AZURE_SUBSCRIPTION_ID`
- `AZURE_RESOURCE_GROUP`
- `AZURE_LOCATION`
- `AZURE_APP_NAME`
- `BACKEND_API_CLIENT_ID`

## No Hallucinated Data Policy

See `docs/no-hallucinated-data-policy.md` for mandatory behavior and test expectations.
