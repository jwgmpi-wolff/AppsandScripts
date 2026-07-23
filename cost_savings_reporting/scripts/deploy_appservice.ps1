param(
    [Parameter(Mandatory = $true)]
    [string]$ResourceGroup,

    [Parameter(Mandatory = $true)]
    [string]$AppName,

    [Parameter(Mandatory = $false)]
    [string]$Location = "eastus",

    [Parameter(Mandatory = $false)]
    [string]$Runtime = "PYTHON:3.11",

    [Parameter(Mandatory = $false)]
    [string]$Sku = "B1"
)

$ErrorActionPreference = "Stop"

Write-Host "Ensuring Azure login session..."
az account show 1>$null
if ($LASTEXITCODE -ne 0) {
    az login
}

Write-Host "Creating resource group $ResourceGroup in $Location..."
az group create --name $ResourceGroup --location $Location --output table

Write-Host "Deploying app code and creating App Service resources..."
az webapp up `
  --name $AppName `
  --resource-group $ResourceGroup `
  --location $Location `
  --runtime $Runtime `
  --sku $Sku

Write-Host "Configuring startup command for FastAPI/Uvicorn..."
az webapp config set `
  --name $AppName `
  --resource-group $ResourceGroup `
  --startup-file "python -m uvicorn dashboard_api:app --host 0.0.0.0 --port 8000" `
  --output table

Write-Host "Setting recommended build app settings..."
az webapp config appsettings set `
  --name $AppName `
  --resource-group $ResourceGroup `
  --settings SCM_DO_BUILD_DURING_DEPLOYMENT=true `
  --output table

Write-Host "Done. App URL: https://$AppName.azurewebsites.net"
