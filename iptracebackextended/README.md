# IPTraceback Extended

IPTraceback Extended is a Node.js + Express application that decodes obfuscated strings, extracts IP addresses, resolves DNS records, classifies addresses, and geolocates destination IPs from one unified web UI and API.

## Quick Start (Local)

### Prerequisites
- Node.js 18+
- npm 9+
- PowerShell (Windows)

### Run locally in one command
```powershell
./deploy-local.ps1
```

### Optional local parameters
```powershell
./deploy-local.ps1 -Port 3001
./deploy-local.ps1 -SkipInstall
```

App URL after launch: `http://localhost:3001`

## Deploy to Azure

### 1) One-click infrastructure button

[![Deploy to Azure](https://aka.ms/deploytoazurebutton)](https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fjerrywolff_microsoft%2Fiptracebackextended%2Fmain%2Finfra%2Fazuredeploy.json)

This creates:
- Linux App Service Plan
- Node.js 20 Linux Web App
- App settings for build during deployment

### 2) Deploy the application code to the created Web App

```powershell
./deploy-azure.ps1 -SubscriptionId "<subscription-guid>" -ResourceGroup "rg-iptraceback" -Location "eastus" -AppName "iptraceback-unique-name"
```

The script will:
- Create resource group and hosting plan
- Create/configure Linux Web App
- Zip and deploy current source
- Print the final public URL

### Azure prerequisites
- Azure CLI installed
- Logged in via `az login`
- Sufficient permission to create Resource Group + App Service resources

## Useful NPM Scripts

```bash
npm start
npm run test
npm run deploy:local
npm run deploy:azure
```

## Project Structure

```text
iptracebackextended/
|- decoders/
|- docs/
|- logs/
|- public/
|- sourceExtractors/
|- utils/
|- infra/
|  |- azuredeploy.json
|- deploy-local.ps1
|- deploy-azure.ps1
|- index.js
|- server.js
|- test.js
|- package.json
|- README.md
```

## API Summary

- `POST /api/decode` decode encoded text and extract IPs
- `POST /api/traceback` full decode + scrape + DNS + classify + geolocate pipeline
- `GET /api/dns/:hostname` DNS record lookup (A, MX, TXT, NS)
- `GET /api/classify/:ip` classify one IP
- `GET /api/geolocate/:ip` geolocate one public IP
- `POST /api/trace` batch classify + geolocate + reverse DNS
- `GET /api/public-ip` discover caller public IP
- `GET /api/cache-clear` clear server-side cache

## Full Regenerate Prompt (GitHub Copilot)

Use this prompt in GitHub Copilot Chat to recreate the entire project from scratch:

```text
Create a complete production-ready project named "IPTraceback Extended" using Node.js (CommonJS) and Express.

Project goals:
1. Build an API + browser UI that discovers destination IP addresses from multiple sources.
2. Support encoded string decoding, URL scraping, hostname DNS resolution, IP classification, and IP geolocation.
3. Provide local deployment and Azure deployment automation scripts.
4. Include infrastructure-as-code template and a Deploy to Azure button path.

Required stack:
- Node.js 18+ (prefer Node 20 runtime compatibility)
- Express for REST endpoints
- Axios or node-fetch for HTTP calls
- Cheerio for HTML scraping
- Native dns and net modules for DNS/IP validation

Required folders/files:
- index.js (main orchestrator class)
- server.js (Express API + static hosting)
- decoders/ (modular decoders)
- sourceExtractors/ (public IP discovery, URL scraping, DNS resolvers)
- utils/ (IP parsing, classification, geolocator, reporting)
- public/index.html (multi-tab UI)
- public/app.js (frontend logic + chaining behavior)
- public/style.css (clean responsive UI)
- test.js (smoke tests)
- package.json
- .gitignore
- deploy-local.ps1
- deploy-azure.ps1
- infra/azuredeploy.json
- README.md

Feature requirements:
A) Decoding
- Auto and manual decode modes.
- Implement Base64, Hex, URL encoding, Binary, Decimal(ASCII), ROT13, Caesar shift.
- Return decoded text plus extracted valid IPv4/IPv6 addresses.

B) URL Scraping + Hostname resolution
- For user-provided URLs, fetch HTML and extract candidate IPs.
- For hostnames, resolve A/MX/TXT/NS records.
- Validate IPs with net.isIPv4/net.isIPv6 and normalize dedupe.

C) Classification
- For each IP return:
  - version (IPv4/IPv6)
  - public/private classification
  - class A/B/C/D/E for IPv4 when applicable
  - numeric form
  - hex and binary representations

D) Geolocation
- Geolocate public IPs using a public API (e.g., ip-api.com).
- Return country, city, region, org/ISP, ASN, timezone, lat/lon.
- Include graceful failures and API timeout handling.

E) Orchestrated traceback
- A single endpoint that performs:
  decode inputs + URL scrape + DNS resolve + classify + optional geolocate.
- Accept arrays with input limits and validation.

F) Cache and safety
- Add in-memory cache with TTL (about 1 hour).
- Limit payload sizes and reject invalid inputs with HTTP 400.
- Return consistent JSON error format.

G) UI
- Build a browser interface with tabs: Decode, Traceback, DNS, Classify, Geolocate.
- Add example chips and copy-to-clipboard UX.
- Add chained execution behavior where useful outputs prefill downstream tabs.
- Make layout responsive for desktop/mobile.

H) Deployment automation
1. Local:
- PowerShell script deploy-local.ps1:
  - verify Node/npm
  - npm ci (optional skip switch)
  - set PORT
  - run npm start

2. Azure:
- PowerShell script deploy-azure.ps1 with params:
  - SubscriptionId, ResourceGroup, Location, AppName, PlanName(optional), Sku(optional)
- Script should:
  - set subscription
  - create resource group
  - create Linux App Service plan
  - create Node 20 web app
  - set SCM_DO_BUILD_DURING_DEPLOYMENT and WEBSITE_NODE_DEFAULT_VERSION
  - package project zip excluding .git/node_modules/logs
  - deploy zip with az webapp deploy
  - print final https URL

3. Infra template:
- infra/azuredeploy.json should provision Linux App Service plan + Linux Node Web App with app settings.

I) README requirements
- Explain project purpose, local run, API summary, folder structure.
- Include Deploy to Azure button that points to raw GitHub URL of infra/azuredeploy.json.
- Include exact command examples for local and Azure deployment.
- Include troubleshooting notes.

Quality requirements:
- Keep code modular and readable.
- Add comments only where logic is non-obvious.
- Validate all external inputs.
- Ensure server starts with npm start.
- Include minimal test script that verifies key functions.

Finally:
- Generate all files with complete content.
- Ensure package.json scripts include start, test, deploy:local, deploy:azure.
- Use MIT license metadata in package.json.
```

## Troubleshooting

- If local run fails on first start, run `npm ci` manually then retry `./deploy-local.ps1`.
- If Azure deploy fails with auth error, run `az login` and `az account set --subscription <id>`.
- If app name is unavailable, choose another globally unique `-AppName`.
- If PowerShell blocks scripts, run:
  ```powershell
  Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
  ```

## License

MIT
