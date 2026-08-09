# One-Request VS Code Deployment Prompt

Open this repository in VS Code and start GitHub Copilot Chat in **Agent** mode. Run `/deploy-finops-copilot` and provide values using this shape:

```text
tenant=<target-tenant-id> subscription=<subscription-id-or-auto> location=<azure-region> publisher=<organization-name> contact=<support-email> publish=<yes-or-no>
```

Example with automatic subscription discovery and a new API deployment:

```text
/deploy-finops-copilot tenant=<target-tenant-id> subscription=auto location=eastus publisher="<organization-name>" contact=<support-email> publish=yes
```

The workspace prompt is stored at [.github/prompts/deploy-finops-copilot.prompt.md](../.github/prompts/deploy-finops-copilot.prompt.md). It instructs Copilot to complete discovery, authentication, a new Azure API deployment, tenant configuration, package build, validation, publication, and smoke testing in one continuous workflow.

This is a fresh deployment into one target tenant, not cross-tenant access. The workflow creates new target-tenant registrations and identifiers and does not reuse another tenant's deployment values.

## Hard Prerequisite

The target tenant must have Microsoft 365 Copilot Chat enabled. The designated deployment/test user must be able to open `https://m365.cloud.microsoft/chat` without being redirected to `/chat/blocked`. The prompt stops before deployment when this prerequisite is not met.

You can also run `/deploy-finops-copilot` without arguments. Copilot will ask once for all missing non-secret deployment values, then start tenant authentication. When the browser account picker reuses the wrong identity, the workflow uses Azure device-code login so you can enter another tenant account directly.

Microsoft administrator sign-ins, consent, and secret entry remain interactive security boundaries. Enter secrets only in the Microsoft Entra or Developer Portal interface when prompted; never paste them into Copilot Chat.

## Copy/Paste Alternative

If prompt files are disabled, paste this into Copilot Chat:

```text
Deploy this repository's FinOps Microsoft 365 Copilot agent end to end. First ask me in one concise questionnaire for every missing non-secret value: target tenant ID or domain, subscription ID or auto, Azure region, publisher name and URLs, support email, whether to publish, and an entitled Microsoft 365 Copilot test user.

Follow finops-copilot-agent/DEPLOYMENT.md and use finops-copilot-agent/setup-tenant.ps1. Authenticate Azure with `az login --tenant <tenant-id> --use-device-code --allow-no-subscriptions` when the current account or tenant differs, and wait for me to complete sign-in directly. Authenticate Agents Toolkit separately; when needed run `atk auth logout m365` and `atk auth login m365 --tenant <tenant-id>`. Never request passwords, MFA codes, secrets, tokens, or cookies in chat. Require Azure tenant ID and post-provision `TEAMS_APP_TENANT_ID` to equal the requested tenant.

Deploy a new compatible API from this repository, configure Entra delegated OAuth, create the tenant setup, build and validate the exact ZIP, inspect the package, extend it to Microsoft 365, publish it, and smoke test it. Pause only for administrator authentication, consent, or secret entry in a secure Microsoft UI. Require `M365_APP_ID` and `M365_TITLE_ID`, run `atk launchinfo --manifest-id <TEAMS_APP_ID>`, generate and open the direct Microsoft 365 Copilot entity URL, and distinguish `/chat/blocked` entitlement failures from deployment failures. Test OAuth and one live bounded read-only report under the test user's Azure RBAC scope. Report every stage as PASS, FAIL, or BLOCKED with evidence; claim completion only when authentication, API health, validation, tenant match, publication, launch, OAuth, and the live report all pass.
```