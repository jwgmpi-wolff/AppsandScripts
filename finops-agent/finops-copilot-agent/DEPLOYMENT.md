# Deploy The FinOps Copilot Agent

This runbook creates a fresh, isolated deployment from this GitHub repository in one Microsoft 365 tenant. It does not configure cross-tenant access or reuse another tenant's registrations, package identifiers, API deployment, or credentials. The target tenant administrator owns every registration and package identifier created for that deployment. End users access Azure through delegated sign-in and RBAC in the target environment.

To have VS Code GitHub Copilot drive this runbook as one continuous workflow, run `/deploy-finops-copilot` and provide the tenant, subscription or `auto`, Azure region, publisher, contact, and publication preference. See the [one-request prompt guide](../docs/COPILOT_ONE_REQUEST_DEPLOYMENT_PROMPT.md).

## What Is Automated

`setup-tenant.ps1` performs the repeatable work:

- Discovers the authenticated Azure tenant and contact email.
- Generates a new Teams app ID.
- Derives the API hostname from its HTTPS URL.
- Writes tenant values to ignored `env/.env.dev`.
- Builds a deterministic ZIP with embedded agent instructions.
- Rejects missing or unresolved variables.
- Runs Microsoft 365 package validation.
- Optionally publishes through Agents Toolkit.

Microsoft requires two administrator-created records that the script cannot create safely: an Entra app credential and a Developer Portal OAuth registration containing that credential.

## 1. Prerequisites

Before deployment begins, the target tenant must have Microsoft 365 Copilot Chat enabled, and the designated deployment/test user must be licensed or otherwise entitled to open `https://m365.cloud.microsoft/chat`. If that user is redirected to `/chat/blocked`, stop: a Microsoft 365 administrator must enable Copilot Chat or assign the required entitlement before the agent can be deployed and verified end to end.

The deployment administrator needs permission in the target tenant to create Entra app registrations and publish custom Teams apps. The test user needs Copilot Chat entitlement and Azure Reader, Cost Management Reader, or other suitable read-only RBAC on the subscriptions they will analyze.

Every deployment requires fresh target-tenant values:

- Entra application and credential.
- Developer Portal OAuth registration.
- Teams app ID and Microsoft 365 app/title IDs.
- New public FinOps API deployment owned by the target tenant.
- Publisher metadata and support contact.

Do not copy these values from an existing tenant or reference deployment.

Install:

```powershell
npm install -g @microsoft/m365agentstoolkit-cli@beta
az --version
atk --version
```

Deploy the FinOps API to a public HTTPS endpoint. The action endpoint must accept an Azure Resource Manager delegated bearer token. Confirm health using the endpoint documented by that API deployment.

For the API's standalone HTML scope selector, pass `-MsalClientId <entra-client-id>` to `deploy-azure.ps1` or set app setting `FINOPS_MSAL_CLIENT_ID` directly, then register the report page URL as an allowed redirect URI. This setting is not a secret.

## 2. Create The Entra Application

In **Microsoft Entra admin center > App registrations**:

1. Create a single-tenant registration in the target tenant.
2. Record its Application (client) ID.
3. Under **Authentication**, add this Web redirect URI:

   ```text
   https://teams.microsoft.com/api/platform/v1.0/oAuthRedirect
   ```

4. Under **API permissions**, add delegated Azure Service Management permission:

   ```text
   https://management.azure.com/user_impersonation
   ```

5. Grant administrator consent if tenant policy requires it.
6. Create a client secret and store it in Key Vault or another managed secret store.

Do not put the secret in `.env.dev`, source control, a command line, or the app package.

## 3. Create The Developer Portal OAuth Registration

In [Teams Developer Portal](https://dev.teams.microsoft.com/tools), open **Tools > OAuth client registration** and create:

| Field | Value |
| --- | --- |
| Base URL | Public FinOps API base URL |
| Organization restriction | This organization only |
| Client ID | Entra Application (client) ID |
| Client secret | Secret from the secure vault |
| Authorization URL | `https://login.microsoftonline.com/<target-tenant-id>/oauth2/v2.0/authorize` |
| Token URL | `https://login.microsoftonline.com/<target-tenant-id>/oauth2/v2.0/token` |
| Refresh URL | Same as token URL |
| Scope | `https://management.azure.com/user_impersonation offline_access openid profile` |
| PKCE | Enabled |
| Client authentication | Request body parameters |

Record the OAuth registration ID returned by Developer Portal. The package references this ID, not the secret.

## 4. Generate The Tenant Package

Authenticate both CLIs to the target tenant:

```powershell
az login --tenant <target-tenant-id>
atk auth login m365
```

Confirm the accounts represent the intended tenant, then run from `finops-copilot-agent`:

```powershell
./setup-tenant.ps1 `
  -ApiBaseUrl https://<finops-api-host> `
  -EntraClientId <entra-client-id> `
  -OAuthRegistrationId <developer-portal-oauth-registration-id> `
  -PublisherName "<organization-name>" `
  -PublisherWebsiteUrl https://<organization-website> `
  -PrivacyUrl https://<organization-privacy-page> `
  -TermsOfUseUrl https://<organization-terms-page> `
  -ContactEmail <support-email>
```

`-TenantId` and `-ContactEmail` are optional when Azure CLI can infer them. Publisher URLs default to the API base URL when omitted, but real organizational pages are recommended for production.

Expected final output includes:

```text
All passed
Package ready: appPackage/build/appPackage.dev.zip
```

The generated `env/.env.dev` is ignored by Git. Compare it with `env/.env.dev.example` when troubleshooting. It contains identifiers and public URLs only.

## 5. Publish

### Automated Path

Add `-Publish` to the setup command. It provisions the Developer Portal app, rebuilds with the assigned Teams app ID, validates again, and submits it to the tenant catalog.

To republish an already provisioned and validated app, run:

```powershell
atk publish --env dev -i false
```

The publish workflow uses the deterministic ZIP created by `setup-tenant.ps1` after provisioning.

### Admin Center Fallback

If Toolkit authentication or tenant policy blocks publication:

1. Open [Teams Admin Center](https://admin.teams.microsoft.com/policies/manage-apps).
2. Select **Teams apps > Manage apps > Upload new app**.
3. Upload `appPackage/build/appPackage.dev.zip`.
4. Verify the external app ID matches `TEAMS_APP_ID` in ignored `env/.env.dev`.
5. Unblock the app and assign it to the intended users or groups.

Tenant catalog propagation can take several minutes.

## 6. Test Delegated Scope

In [Microsoft 365 Copilot](https://m365.cloud.microsoft/chat/agentstore), add the agent from **Built by your org** and ask:

```text
Show the highest-impact optimization opportunities for my subscriptions.
```

When prompted, provide the target tenant and choose subscriptions. Complete OAuth sign-in and verify the returned scope. The result must be limited to subscriptions available to the signed-in user.

The deployment administrator's Azure access is not inherited by users. Grant each user or group suitable Azure RBAC separately.

## Change Or Add A Tenant

For every additional tenant:

1. Create a new Entra app and secret in that tenant.
2. Create a new Developer Portal OAuth registration in that tenant.
3. Use that tenant's API endpoint and publisher details.
4. Authenticate Azure CLI and Agents Toolkit to that tenant.
5. Rerun `setup-tenant.ps1`; it creates a fresh Teams app ID by default.
6. Publish and assign the resulting app in that tenant.

To update an existing app instead of creating a new identity, pass its existing ID with `-TeamsAppId`.

Never reuse another tenant's API, client secret, or OAuth registration ID. Deploy a new API instance owned by the target tenant for each fresh deployment.

## Security And Operations

- Keep all actions read-only.
- Keep client secrets only in Entra, Developer Portal, and a managed vault.
- Rotate the secret before expiration and update Developer Portal before deleting the old credential.
- Restrict the OAuth registration to the final Teams app ID after testing when that option is available.
- Remove a user's Azure RBAC to remove their report access.
- Rebuild and republish after changing tenant IDs, URLs, scopes, or publisher information.

The [historical deployment transcript](../docs/COPILOT_DEPLOYMENT_TRANSCRIPT.md) contains reference-environment values for evidence only. They are not defaults and must not be reused.
