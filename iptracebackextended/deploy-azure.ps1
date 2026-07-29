param(
  [Parameter(Mandatory = $true)]
  [string]$SubscriptionId,

  [Parameter(Mandatory = $true)]
  [string]$ResourceGroup,

  [Parameter(Mandatory = $false)]
  [string]$Location = 'eastus',

  [Parameter(Mandatory = $true)]
  [string]$AppName,

  [Parameter(Mandatory = $false)]
  [string]$PlanName = '',

  [Parameter(Mandatory = $false)]
  [string]$Sku = 'B1'
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
  throw "Azure CLI is not installed. Install Azure CLI and retry."
}

if ([string]::IsNullOrWhiteSpace($PlanName)) {
  $PlanName = "$AppName-plan"
}

Write-Host "[azure] Setting subscription $SubscriptionId" -ForegroundColor Cyan
az account set --subscription $SubscriptionId

Write-Host "[azure] Creating resource group $ResourceGroup in $Location" -ForegroundColor Cyan
az group create --name $ResourceGroup --location $Location | Out-Null

Write-Host "[azure] Creating Linux App Service plan $PlanName ($Sku)" -ForegroundColor Cyan
az appservice plan create `
  --name $PlanName `
  --resource-group $ResourceGroup `
  --location $Location `
  --is-linux `
  --sku $Sku | Out-Null

Write-Host "[azure] Creating web app $AppName" -ForegroundColor Cyan
az webapp create `
  --name $AppName `
  --resource-group $ResourceGroup `
  --plan $PlanName `
  --runtime "NODE:20-lts" | Out-Null

Write-Host "[azure] Configuring app settings" -ForegroundColor Cyan
az webapp config appsettings set `
  --resource-group $ResourceGroup `
  --name $AppName `
  --settings SCM_DO_BUILD_DURING_DEPLOYMENT=true WEBSITE_NODE_DEFAULT_VERSION=~20 | Out-Null

$zipPath = Join-Path $PSScriptRoot "deploy-package.zip"
if (Test-Path $zipPath) {
  Remove-Item $zipPath -Force
}

Write-Host "[azure] Packaging application" -ForegroundColor Cyan
$itemsToZip = Get-ChildItem -Path $PSScriptRoot -Force | Where-Object {
  $_.Name -notin @('.git', 'node_modules', 'logs', '.vscode', '.idea', 'deploy-package.zip')
}
Compress-Archive -Path $itemsToZip.FullName -DestinationPath $zipPath -Force

Write-Host "[azure] Deploying package to web app" -ForegroundColor Cyan
az webapp deploy `
  --resource-group $ResourceGroup `
  --name $AppName `
  --src-path $zipPath `
  --type zip | Out-Null

Remove-Item $zipPath -Force

$hostName = az webapp show --resource-group $ResourceGroup --name $AppName --query defaultHostName -o tsv
Write-Host "[azure] Deployment complete: https://$hostName" -ForegroundColor Green
