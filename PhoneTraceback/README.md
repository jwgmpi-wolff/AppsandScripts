# Number Origin Desk

Number Origin Desk is a privacy-preserving Android and web application for validating a phone number and showing general numbering-assignment metadata. It does not locate a caller, device, subscriber, or network route.

## Safety boundary

The application intentionally does not perform real-time tracking, subscriber deanonymization, carrier traceback, SS7/SIGTRAN tracing, or covert location discovery. Phone numbers can be spoofed, reassigned, ported, or used outside their assigned region. Every successful result includes this warning:

> Numbering-plan assignment only. Not a live caller, device, or network location. Caller ID may be spoofed.

For North American Numbering Plan numbers, the app can display the three-digit area code as assignment metadata. Area codes can be ported, reassigned, used remotely, or spoofed and must not be interpreted as the call's physical origin.

The server does not persist submitted phone numbers. Production operators should still treat application and proxy logs as potentially sensitive and configure retention accordingly.

The Android app operates independently of the web application, Azure, and USB. Number parsing, assignment inspection, call-history matching, saving, and export all happen on the device. The optional Scam Phone report check sends the selected ten-digit NANP number to ReportedCalls.com only after the user selects **Live Bing abuse-report search**. The number is held in transient action state and cleared when that request finishes. A successful result is written to app-private storage only if the user subsequently selects **Save investigation and scam report**. It can optionally inspect matching entries in the device call history after an explicit runtime permission prompt. Call-log access is restricted by Google Play policy, so public distribution may require removing this feature or qualifying for an allowed use.

Investigations are saved only when the user selects the save action. Reports use structured JSON in app-private storage with Android backup disabled. Saved Scam Phone data includes the public summary, assignment, source URL, and retrieval time. The saved-investigations section exports a readable PDF through Android's document picker for later analysis or submission to a provider or regulator. Exports contain phone numbers and call metadata and should be handled as sensitive data.

## Project layout

- `web/`: Node.js/Express API and browser client.
- `android/`: standalone Kotlin/Jetpack Compose Android application.
- `infra/`: private Azure App Service infrastructure and deployment automation.
- `releases/NumberOriginDesk.apk`: installable APK for authorized testing.
- `docs/screenshots/`: privacy-safe browser screenshot.

## Data sources

- [libphonenumber-js](https://github.com/catamphetamine/libphonenumber-js) parses and validates international numbers from numbering-plan metadata.
- [Countries](https://github.com/mledoze/countries), bundled through `world-countries`, supplies versioned country display names without an outbound lookup.

Each API result reports its source URLs and UTC retrieval time. Non-geographic numbering plans return validated metadata without inferred location.

The investigation section provides a live exact-number Google search, an in-app ReportedCalls.com Scam Phone report check triggered by the Bing abuse button, current NANPA reports, and official FCC/FTC reporting links. The Scam Phone result includes the public assignment and complaint summary returned by that third-party site. These results are leads, not proof that reports concern the current caller. They do not provide a live caller location, carrier route, subscriber identity, or STIR/SHAKEN attestation. An authoritative traceback requires the involved voice providers and is generally initiated promptly through a provider, law enforcement, or the appropriate regulator. Preserve the original call time, time zone, displayed number, voicemail, and screenshots when reporting abuse.

## Run the web app

Requirements: Node.js 20 or newer.

```powershell
cd web
npm install
npm test
npm start
```

Open <http://localhost:3000>. The health endpoint is <http://localhost:3000/health>.

Example API request:

```text
GET /api/lookup?number=%2B1%20202%20555%200123
```

The endpoint accepts international-format numbers only, limits requests per IP, and does not return subscriber or live-location data.

## Run the Android app

Open `android/` in Android Studio with JDK 17 and Android SDK 35 installed, then build and install the application. The installed app does not require the web server, Azure deployment, USB connection, ADB tunnel, account, or network access for number inspection and local reporting.

The live Google search, in-app Scam Phone report check, and NANPA/FCC/FTC links require network access after the user selects them. Scam Phone report checks currently support ten-digit North American Numbering Plan numbers. Build a signed release in Android Studio for distribution.

### Download the Android APK

[Download Number Origin Desk for Android](https://github.com/jerrywolff_microsoft/PhoneTraceback/releases/download/v1.0.0/NumberOriginDesk.apk)

This verified APK is for authorized testing. Because the repository is private, GitHub may require an account with repository access before downloading it. On the Android device, open the downloaded APK, allow installation from that browser or file manager when prompted, and select **Install**. No USB connection or web service is required afterward.

SHA-256: `1D3379E3F7C1CA40519804405384D4E9C7712D470E3E3A5E3EF50B99BA47AABC`

## Validation

The local validation gate runs `npm ci`, all web tests, the Android debug build, PowerShell parsing, ARM JSON parsing, secret-pattern checks, and APK checksum comparison. The same web and Android jobs are defined in `.github/workflows/validate.yml`. GitHub Enterprise currently blocks hosted runners for this private repository, so an enterprise administrator must enable hosted runners or provide compatible self-hosted runners before cloud CI jobs can execute.

## Deploy the web app to Azure

Requirements: Azure CLI, an active `az login` session, and permission to create resource groups, App Service, networking, private DNS, Entra applications, and service principals in the selected tenant and subscription.

```powershell
az login
az account set --subscription <subscription-id>

cd infra
.\deploy-azure.ps1 `
  -ResourceGroup rg-number-origin-desk `
  -Location westus2 `
  -AppName <globally-unique-app-name>
```

The script creates or reuses a single-tenant Entra application, creates a one-year credential, deploys a Linux App Service with Authentication V2, and provisions a VNet, private endpoint, and linked `privatelink.azurewebsites.net` private DNS zone. It temporarily permits public access for zip deployment, then disables the public endpoint. The generated credential is passed through an ignored temporary parameter file and deleted in a `finally` block; it is never printed or committed.

The App Service URL is not reachable from the general internet after deployment. Users need both:

- An account in the configured Microsoft Entra tenant.
- Network reachability through point-to-site/site-to-site VPN, ExpressRoute, or a client hosted in or peered with the VNet.

Clients must use DNS that can resolve the App Service name through the linked private zone. For on-premises or VPN clients, configure conditional forwarding for `privatelink.azurewebsites.net` to Azure DNS Private Resolver or an equivalent DNS forwarder in the VNet. The normal URL remains `https://<app-name>.azurewebsites.net`; do not browse directly to the private endpoint IP.

The default B1 plan incurs Azure charges. Delete the resource group when it is no longer needed:

```powershell
az group delete --name rg-number-origin-desk
```

## Production notes

- Rotate the Entra application credential before the expiration date printed by the deployment script.
- Configure Application Insights and an appropriate log-retention policy before production use.
- Keep application and platform dependencies patched.
- Do not market or display results as caller location, ownership, identity, or traceback evidence.