---
name: "Deploy FinOps Copilot"
description: "Interview for tenant details, authenticate safely, and deploy and verify the FinOps agent end to end in Microsoft 365 Copilot."
argument-hint: "Optional: tenant=<tenant-id> subscription=<id-or-auto> location=<region> publisher=<organization> contact=<email> publish=<yes-or-no>"
agent: "agent"
---

Create a fresh, isolated deployment of this GitHub repository's FinOps Microsoft 365 Copilot agent in one target tenant. Do not configure cross-tenant access and do not reuse another tenant's API, registrations, app IDs, package IDs, publisher values, or credentials. If my request omits deployment values, interview me for them before changing resources.

Use [the deployment runbook](../../finops-copilot-agent/DEPLOYMENT.md), [the tenant setup script](../../finops-copilot-agent/setup-tenant.ps1), and the repository's existing Azure infrastructure. Stay with the task through prerequisite checks, deployment, package validation, publication when requested, and smoke testing.

Requirements:

1. First ask for any missing non-secret inputs in one concise questionnaire: target tenant ID or verified domain, subscription ID or `auto`, Azure region, publisher name, publisher website, privacy URL, terms URL, support email, publication choice, and a test user with Microsoft 365 Copilot Chat entitlement. Treat the confirmed tenant as authoritative.
2. Before creating or changing anything, open `https://m365.cloud.microsoft/chat` as the designated target-tenant test user. Require Copilot Chat to load. If it redirects to `/chat/blocked`, report the prerequisite as BLOCKED and stop until a Microsoft 365 administrator enables Copilot Chat or assigns the required entitlement. Do not deploy an agent that cannot be launched and tested.
3. Start read-only. Verify Git is clean and check PowerShell, Azure CLI, Python, Azure Functions Core Tools when needed, Node.js, and Microsoft 365 Agents Toolkit. Do not commit generated packages or tenant-local environments.
4. Authenticate Azure explicitly to the requested tenant. If the current identity or tenant differs, run `az login --tenant <tenant-id> --use-device-code --allow-no-subscriptions`, show the device URL/code, and wait for me to sign in directly with the intended account. Never ask for my password, MFA code, token, or browser cookie in chat. Run `az account show` and require its tenant ID to equal the target.
5. Discover enabled subscriptions in that tenant. Use the requested subscription, or ask me to select one when discovery returns multiple choices. Set it explicitly and recheck tenant, account, and subscription. Stop if the identity cannot access the target tenant.
6. Authenticate Microsoft 365 Agents Toolkit separately. Show `atk auth list`. If the account is absent or wrong, run `atk auth logout m365`, then `atk auth login m365 --tenant <tenant-id>` and wait for browser authentication. Do not assume Azure CLI and Toolkit share credentials.
7. Validate and deploy a new instance of this repository's FinOps agent API into the selected target-tenant subscription using the repository's existing infrastructure. Use least privilege, never deploy into another tenant, and confirm its health and `/api/agent/reports/{domain}` contract before packaging.
8. Create a new target-tenant single-tenant Entra application for delegated Azure Resource Manager access. Configure `https://teams.microsoft.com/api/platform/v1.0/oAuthRedirect` and delegated `https://management.azure.com/user_impersonation`. Do not request, display, log, or save a client secret in chat, source control, command arguments, or environment files.
9. If secret creation or Developer Portal OAuth registration requires secure manual entry, pause at that exact step. Tell me only the non-secret fields to enter, have me enter the secret directly in the Microsoft UI, and resume after I provide only the public Entra client ID and OAuth registration ID. Never route a secret through Copilot Chat.
10. Run `finops-copilot-agent/setup-tenant.ps1` with the confirmed tenant's newly deployed API, Entra, OAuth, Teams, publisher, and contact values. Keep `.env.dev` ignored and secret-free. Use `-Publish` only after I confirmed publication.
11. Build the package and validate the exact ZIP with `atk validate --package-file <zip> --validate-method validation-rules`. Inspect it and prove that:
   - OAuth authorization and token URLs use the selected tenant.
   - The API server and valid domain use the selected API host.
   - Teams app and OAuth registration IDs are the selected tenant's values.
   - No `${{...}}` or `$[file(...)]` build placeholders remain.
   - Adaptive Card `${if(...)}` runtime bindings remain intact.
   - No secret, token, password, private key, or credential is packaged.
12. During provisioning, require `TEAMS_APP_TENANT_ID` to equal the requested tenant before continuing. Require `M365_APP_ID` and `M365_TITLE_ID` after `copilotAgent/publish`. If any tenant differs, stop before catalog publication and tell me how to switch the Toolkit account.
13. When publication is approved, rebuild after Teams app ID assignment, revalidate the exact ZIP, publish to the tenant catalog, and verify availability. Use Teams Admin Center upload only as the documented fallback.
14. Run `atk launchinfo --manifest-id <TEAMS_APP_ID>` and require success. Generate the Microsoft 365 Copilot direct entity URL from `M365_APP_ID`, open it, and verify the named agent loads. If redirected to `/chat/blocked`, report a Copilot Chat license or tenant-policy entitlement blocker; do not misdiagnose it as an agent deployment failure.
15. Perform an end-to-end smoke test with the entitled test user and an accessible subscription. Confirm OAuth sign-in, a live bounded API invocation, returned tenant/subscription scope, no mock data, and read-only behavior. Azure RBAC must remain the authorization boundary.
16. Report every stage as PASS, FAIL, or BLOCKED with evidence. Deployment is complete only when authentication, API health, package validation, tenant match, Microsoft 365 extension, catalog publication, direct launch, OAuth, and live report smoke test all pass. Never claim success for skipped stages.
17. Do not commit tenant-local environment files, generated packages, public tenant IDs produced by a deployment, tokens, or credentials. Commit generic source/documentation fixes only when I explicitly request it.

At completion, return:

- Target tenant, account, subscription, and API host.
- Entra app ID, Teams app ID, Microsoft 365 app/title IDs, and OAuth registration ID (public identifiers only).
- Build, exact-ZIP validation, tenant-match, extension, publication, launch, API health, OAuth, and live smoke-test results.
- Any manual administrator action still required.
- Exact cleanup, redeployment, and secret-rotation commands or paths.