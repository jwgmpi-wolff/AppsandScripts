# Regeneration Prompt

Rebuild Number Origin Desk as a privacy-preserving Node.js/Express web application and Kotlin/Jetpack Compose Android application.

## Required behavior

- Accept international-format phone numbers and validate them with libphonenumber metadata.
- Return and display canonical formatting, country calling code, numbering country, number type, and NANP area-code digits when applicable.
- Describe every result as numbering-assignment metadata only. Never infer, calculate, map, or display a caller, device, subscriber, carrier, gateway, or network location.
- State that numbers can be spoofed, ported, reassigned, or used outside their assigned numbering region.
- Provide live exact-number Google/Bing abuse-report searches, current NANPA reports, and official FCC/FTC reporting links as leads, never proof.
- Keep server lookups stateless and rate limited.
- Keep web and Android as independent applications. Android number inspection and local reporting must not call the web API, Azure, localhost, or require USB, ADB forwarding, an account, or network access.
- On Android, request call-log permission only after explicit user action, analyze matching records on-device, and never upload call records.
- Store investigations only after explicit user action in app-private JSON with backup disabled, and export only through Android's document picker.

## Technology and validation

- Web: Node.js 20+, Express, Helmet, express-rate-limit, libphonenumber-js, world-countries, Node test runner, and Supertest.
- Android: Kotlin, Jetpack Compose Material 3, JDK 17, compile/target SDK 35, min SDK 26, and libphonenumber-android.
- Include focused API tests proving that no coordinate field is returned.
- Build a debug APK and verify a number lookup succeeds on an authorized Android device with no ADB reverse mapping and no Internet permission.

## Azure deployment

- Deploy the web application to Linux Azure App Service with Node.js 24 LTS, HTTPS only, TLS 1.2+, HTTP/2, and FTPS disabled.
- Require single-tenant Microsoft Entra App Service Authentication V2.
- Provision a VNet, dedicated private-endpoint subnet, App Service private endpoint, and linked `privatelink.azurewebsites.net` private DNS zone.
- Temporarily enable public access only for zip deployment, then disable it.
- Never commit or print credentials. Use an ignored temporary secure parameter file and delete it in a `finally` block.
- Document VPN, ExpressRoute, peering, and private-DNS forwarding requirements.
- Run ARM validation and `what-if` before deployment.

Preserve the restrained existing visual language, launcher icon, privacy boundary, local deployment script, GitHub validation workflow, release APK path, and documentation structure.
