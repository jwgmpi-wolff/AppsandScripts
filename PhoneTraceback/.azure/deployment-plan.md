# Azure Deployment Plan

**Status:** Approved
**Prepared:** 2026-07-28
**Recipe:** AZCLI / Azure App Service zip deployment

## 1. Workload

Deploy the existing Number Origin Desk Node.js/Express web application from `web/` behind tenant-only Microsoft Entra authentication and an App Service private endpoint. The Android application and APK remain repository artifacts and are not hosted as an Azure compute workload.

## 2. Architecture

- Azure Resource Group: new or existing deployment boundary.
- Linux Azure App Service Plan: B1 by default; paid, single-region.
- Azure App Service: Node.js 24 LTS, HTTPS only, TLS 1.2 minimum, HTTP/2 enabled, FTPS disabled.
- App Service Authentication V2: single-tenant Entra application, authentication required, unauthenticated users redirected to Entra login.
- Azure VNet: dedicated private-endpoint subnet with private endpoint network policies disabled.
- App Service private endpoint: `sites` group, approved in the deployment.
- Private DNS: `privatelink.azurewebsites.net` linked to the VNet and associated with the private endpoint.
- Public network access: enabled only for zip deployment, then disabled by the deployment script.
- Deployment: local zip package generated from `web/package.json`, `web/package-lock.json`, `web/src/`, and `web/public/`.
- No database, map provider, storage account, or embedded source-control credentials.

## 3. Security And Privacy

- Use the caller's current Azure CLI identity; do not store credentials.
- Generate an Entra app credential for App Service Authentication, place it only in an ignored temporary parameter file, and remove it in `finally`.
- Restrict identity access to the selected tenant and network access to clients connected through VPN, ExpressRoute, peering, or the VNet.
- Submitted phone numbers are processed in memory and are not persisted by the server.
- App Service and proxy logs may contain request URLs; production operators must set suitable retention and access controls.
- Results are numbering-plan metadata, never live location or authoritative carrier traceback.

## 4. Deployment Inputs

- Subscription: confirm active Azure CLI subscription before deployment.
- Region: `westus2` unless the user selects another supported region.
- Resource group: `rg-number-origin-desk`.
- App name: choose a globally unique `number-origin-desk-*` name after availability checking.
- SKU: `B1` (ongoing Azure charges apply).
- VNet: `vnet-number-origin-desk`, `10.42.0.0/16`; private endpoint subnet `10.42.1.0/24` by default.
- Entra application: create or reuse the script's single-tenant display name and configure only the App Service callback URL.

## 5. Preparation Checklist

- [x] Web tests pass locally.
- [x] Android debug APK builds locally.
- [x] PowerShell deployment scripts parse successfully.
- [x] ARM template parses successfully.
- [x] Browser smoke test renders assignment metadata without map or coordinate behavior.
- [x] Azure deployment script uses HTTPS-only App Service and transient secure credential handling.
- [x] Deployment is approved by the user instruction to complete all listed items.

## 6. Validation Steps

- [ ] Confirm Azure authentication, subscription, and target location.
- [ ] Confirm provider/resource availability and app-name availability.
- [ ] Run `npm ci` and `npm test` in `web/`.
- [ ] Parse `infra/deploy-azure.ps1` and `infra/azuredeploy.json`.
- [ ] Validate ARM template with Azure Resource Manager where applicable.
- [ ] Run resource-group deployment `what-if` and inspect all planned changes.
- [ ] Confirm private endpoint, private DNS zone/link, and Auth V2 configuration are accepted in the target region.
- [ ] Confirm zip package contains application files at archive root.
- [ ] Record command results and timestamp below.

## 7. Validation Proof

- Local validation: `npm ci`, `npm test` (3/3 passing), Android `:app:assembleDebug`, PowerShell parsing, and ARM JSON parsing all pass.
- Android release: `releases/NumberOriginDesk.apk`, SHA-256 `1D3379E3F7C1CA40519804405384D4E9C7712D470E3E3A5E3EF50B99BA47AABC`; installed package was verified on device `R3CT60BCL0E` with no ADB reverse mapping.
- GitHub: private repository `https://github.com/jerrywolff_microsoft/PhoneTraceback`, branch `main`, initial commit `40a95dc523727401c5aa978907a9f3898585b594`.
- GitHub release: `https://github.com/jerrywolff_microsoft/PhoneTraceback/releases/tag/v1.0.0` with `NumberOriginDesk.apk` (10,011,779 bytes).
- GitHub Actions workflow is valid but hosted runners are disabled by GitHub Enterprise policy. Both jobs were rejected before executing steps; an enterprise administrator must enable hosted runners or provide self-hosted runners.
- Azure CLI context: subscription `wpisub` (`9ea136a4-ccfd-45c2-a8e5-01a38c0563d3`), tenant `7fcb095b-344f-4c1e-8e12-bc781eb03e72`.
- Azure Resource Manager validation and what-if remain pending a Conditional Access `p1` claims-challenge MFA token for write operations.

## 8. Deployment And Verification

1. Execute `infra/deploy-azure.ps1` using the confirmed context.
2. From a VNet-connected client, verify the app hostname resolves to the private endpoint address.
3. Verify an unauthenticated request redirects to the configured tenant's Entra login.
4. Authenticate with a tenant account and verify `/health`, the browser UI, and a sample lookup.
5. Confirm public network access is disabled and the site is unreachable from a general internet client.
6. Record the private HTTPS URL and validation evidence below. No destructive cleanup is performed automatically.

## 9. Client Network Prerequisites

- Connect through point-to-site/site-to-site VPN, ExpressRoute, a peered VNet, or a VNet-hosted client.
- Link the private DNS zone to every VNet whose clients need access, or configure DNS forwarding.
- On-premises and VPN DNS should conditionally forward `privatelink.azurewebsites.net` to Azure DNS Private Resolver or a VNet DNS forwarder.
- Use `https://<app>.azurewebsites.net`; TLS and Entra callback handling rely on the public hostname resolving privately.
