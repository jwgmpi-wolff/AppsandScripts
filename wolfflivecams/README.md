# Wolff Live Cams

A production-ready starter for a Python FastAPI app with:

- Local deployment script (run on your computer)
- Azure App Service deployment script
- Azure infrastructure template
- GitHub Actions workflow for Azure deploy
- Full Copilot regeneration prompt

## Deploy To Azure Button

Use this button to deploy directly from this repository.

[![Deploy to Azure](https://aka.ms/deploytoazurebutton)](https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fjerrywolff_microsoft%2Fwolfflivecams%2Fmain%2Finfra%2Fazuredeploy.json)

Direct link:

https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fjerrywolff_microsoft%2Fwolfflivecams%2Fmain%2Finfra%2Fazuredeploy.json

## Project Structure

```
.
├─ app/
│  ├─ main.py
│  └─ templates/
│     └─ index.html
├─ infra/
│  └─ azuredeploy.json
├─ .github/workflows/
│  └─ deploy-azure-webapp.yml
├─ deploy-local.ps1
├─ deploy-azure.ps1
├─ requirements.txt
└─ README.md
```

## Local Deployment (Your Computer)

Run in PowerShell:

```powershell
Set-Location <path-to-repo>
.\deploy-local.ps1 -Port 8000
```

Open:

- Home: http://localhost:8000/
- Health: http://localhost:8000/health

## Azure Deployment Functionality

### Option A: One-command PowerShell deployment

Prereqs:

- Azure CLI installed and logged in (`az login`)
- A unique web app name

Deploy:

```powershell
Set-Location <path-to-repo>
.\deploy-azure.ps1 -ResourceGroup rg-wolfflivecams -Location eastus -AppName wolfflivecams-prod-001
```

This will:

1. Create/update the resource group
2. Deploy App Service plan + Web App using `infra/azuredeploy.json`
3. Package app code
4. Zip-deploy to Azure Web App

### Option B: GitHub Actions deployment

Workflow file: `.github/workflows/deploy-azure-webapp.yml`

Set these GitHub repository secrets:

- `AZURE_CLIENT_ID`
- `AZURE_TENANT_ID`
- `AZURE_SUBSCRIPTION_ID`

Then push to `main` or run the workflow manually from Actions.

## Full Regenerate Prompt For GitHub Copilot

Use this full prompt in GitHub Copilot Chat to recreate this entire project from scratch:

```text
Create a complete Python FastAPI project named "Wolff Live Cams" in the current workspace.

Requirements:
1) App code
- Create app/main.py with a FastAPI app.
- Add GET / endpoint returning an HTML page from Jinja template.
- Add GET /health returning JSON {"status":"ok"}.
- Keep code clean, readable, and minimal.

2) Frontend template
- Create app/templates/index.html.
- Build a polished responsive page with a light, sky-themed style.
- Include project title, running status badge, and a Health link button.

3) Dependencies
- Create requirements.txt with:
	- fastapi==0.116.1
	- uvicorn[standard]==0.35.0
	- jinja2==3.1.6
	- gunicorn==23.0.0

4) Local deployment functionality
- Create deploy-local.ps1 that:
	- Creates .venv if missing
	- Activates .venv
	- Installs dependencies from requirements.txt
	- Runs uvicorn app.main:app on configurable port (default 8000)

5) Azure deployment functionality
- Create infra/azuredeploy.json ARM template that deploys:
	- Microsoft.Web/serverfarms (Linux App Service plan)
	- Microsoft.Web/sites (Linux Web App, Python 3.11)
	- Startup command: gunicorn -k uvicorn.workers.UvicornWorker app.main:app
	- App settings including SCM_DO_BUILD_DURING_DEPLOYMENT=true and WEBSITE_RUN_FROM_PACKAGE=1
- Create deploy-azure.ps1 script that:
	- Accepts ResourceGroup, Location, AppName, optional PlanName and Sku
	- Creates resource group
	- Deploys ARM template
	- Packages app + requirements into zip
	- Runs az webapp deployment source config-zip

6) GitHub Actions deployment
- Create .github/workflows/deploy-azure-webapp.yml that:
	- Triggers on push to main and workflow_dispatch
	- Uses azure/login with OIDC secrets
	- Builds release zip
	- Deploys via azure/webapps-deploy

7) Repo hygiene
- Create .gitignore including .venv, __pycache__, *.pyc, .env, *.zip, .publish

8) README.md
- Write a complete README including:
	- Project overview
	- Local deployment steps
	- Azure deployment steps (script + GitHub Actions)
	- Deploy to Azure button using portal URL format and raw GitHub template path
	- Project structure tree
	- This exact regenerate prompt block

9) Validation
- Ensure files are syntactically correct.
- Keep everything ASCII.
- Do not add extra services or files beyond what is required.
```

## Quick Start

```powershell
.\deploy-local.ps1
```

## Notes

- `deploy-azure.ps1` uses Azure CLI and your signed-in identity.
- For production hardening, add custom domain, managed identity, and secrets in Azure Key Vault.
