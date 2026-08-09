# FinOps CCoE Copilot Agent

A Microsoft 365 Copilot declarative agent that runs read-only Azure FinOps reports with the signed-in user's delegated Azure permissions.

The package is tenant-neutral. Each tenant administrator supplies that tenant's Entra application, OAuth registration, API endpoint, and publisher information. No client secret is stored in the repository or generated environment file.

## Deploy In Three Steps

### 1. Create The Two Tenant Registrations

In the target tenant:

1. Create a single-tenant Entra app registration.
2. Add the Web redirect URI `https://teams.microsoft.com/api/platform/v1.0/oAuthRedirect`.
3. Add delegated Azure Service Management permission `https://management.azure.com/user_impersonation`.
4. Create a client secret and keep it in a secure vault.
5. In [Teams Developer Portal](https://dev.teams.microsoft.com/tools), create an OAuth client registration using that client ID and secret.

Record the Entra client ID and Developer Portal OAuth registration ID. These are public identifiers; the secret is entered only in Developer Portal.

### 2. Configure, Build, And Validate

Install the tools and authenticate to the target tenant:

```powershell
npm install -g @microsoft/m365agentstoolkit-cli@beta
az login --tenant <target-tenant-id>
atk auth login m365
```

From this directory, run:

```powershell
./setup-tenant.ps1 `
  -ApiBaseUrl https://<finops-api-host> `
  -EntraClientId <entra-client-id> `
  -OAuthRegistrationId <developer-portal-oauth-registration-id> `
  -PublisherName "<organization-name>" `
  -PublisherWebsiteUrl https://<organization-website> `
  -PrivacyUrl https://<organization-privacy-page> `
  -TermsOfUseUrl https://<organization-terms-page>
```

The script discovers the Azure tenant and signed-in contact, generates a Teams app ID, derives the API host, writes ignored `env/.env.dev`, builds the package, and validates it. The result is:

```text
appPackage/build/appPackage.dev.zip
```

Pass `-TenantId` or `-ContactEmail` when Azure CLI cannot infer the correct value. To inspect configuration without building, pass `-SkipPackage`.

### 3. Publish

For CLI publication, add `-Publish` to the setup command. It provisions the Developer Portal app, rebuilds with the assigned app ID, validates again, and submits it to the tenant catalog. The signed-in Microsoft 365 account must be allowed to publish custom apps.

If tenant policy blocks CLI publication, upload `appPackage/build/appPackage.dev.zip` through **Teams Admin Center > Teams apps > Manage apps > Upload new app**. Make the app available to the intended users or groups, then add it from **Built by your org** in Microsoft 365 Copilot.

## Runtime Scope

Deployment credentials do not grant Azure data access. At runtime:

- The user signs in to the tenant-specific OAuth connection.
- The agent asks for the tenant and subscription scope when it is not already supplied.
- An empty subscription list means all subscriptions visible to that user.
- Azure RBAC determines which subscriptions and resources the user can read.
- The agent reports and recommends; it does not modify Azure resources.

To use a different tenant, create registrations in that tenant and rerun `setup-tenant.ps1` with the new IDs and API URL. A fresh Teams app ID is generated unless `-TeamsAppId` is supplied.

See [DEPLOYMENT.md](DEPLOYMENT.md) for the complete administrator workflow and [the cross-tenant guide](../docs/COPILOT_TENANT_DEPLOYMENT.md) for a concise migration checklist. The [reference transcript](../docs/COPILOT_DEPLOYMENT_TRANSCRIPT.md) records one historical deployment only; do not reuse its IDs.

For a single-request VS Code workflow, run `/deploy-finops-copilot` in GitHub Copilot Chat. See the [one-request deployment prompt](../docs/COPILOT_ONE_REQUEST_DEPLOYMENT_PROMPT.md) for invocation examples and a copy/paste alternative.

## Troubleshooting

- **Validation fails:** confirm every setup value belongs to the target tenant and rerun the script.
- **OAuth loops:** verify the callback URI, tenant-specific authorization URLs, secret, PKCE, and delegated scope.
- **The action returns 401:** remove the agent connection in Copilot, sign in again, and verify the user's Azure RBAC.
- **The app is missing:** allow custom apps, assign the app to the user, and wait for tenant catalog propagation.
- **Browser report sign-in is unavailable:** set backend app setting `FINOPS_MSAL_CLIENT_ID` to the tenant's Entra client ID and configure the report URL as an allowed redirect URI.
