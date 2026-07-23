# Azure FinOps Savings Reporting Dashboard

Landing page for the `cost_savings_reporting` project.

[![Deploy to Azure](https://aka.ms/deploytoazurebutton)](https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fjerrywolff_microsoft%2Fcost_savings_reporting%2Fmain%2Fcost_savings_reporting%2Finfra%2Fazuredeploy.json)

## Quick Start

1. Open the full project documentation: [cost_savings_reporting/README.md](cost_savings_reporting/README.md)
2. Run locally (Windows PowerShell):

```powershell
cd cost_savings_reporting
./scripts/deploy_local.ps1
```

3. Open: `http://127.0.0.1:8000`

## What This Deploy Button Does

The Deploy to Azure button provisions the FinOps dashboard App Service resources using:

- `cost_savings_reporting/infra/azuredeploy.json`
- `cost_savings_reporting/infra/azuredeploy.parameters.json`
