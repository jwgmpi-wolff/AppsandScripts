param(
    [Parameter(Mandatory = $true)][string]$ResourceGroup,
    [Parameter(Mandatory = $true)][string]$Location,
    [Parameter(Mandatory = $true)][string]$AppName,
    [string]$PlanName = "${AppName}-plan",
    [string]$Sku = "B1"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$project = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $project

if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    throw 'Azure CLI (az) is not installed. Install it first and retry.'
}

Write-Host '[azure] Verifying Azure login...' -ForegroundColor Cyan
az account show | Out-Null

Write-Host "[azure] Creating resource group $ResourceGroup in $Location" -ForegroundColor Cyan
az group create --name $ResourceGroup --location $Location | Out-Null

Write-Host '[azure] Deploying infrastructure template...' -ForegroundColor Cyan
az deployment group create `
  --resource-group $ResourceGroup `
  --template-file .\infra\azuredeploy.json `
  --parameters siteName=$AppName serverFarmName=$PlanName location=$Location skuName=$Sku | Out-Null

if (Test-Path '.publish') {
    Remove-Item -Recurse -Force '.publish'
}
New-Item -ItemType Directory -Path '.publish' | Out-Null

Write-Host '[azure] Preparing deployment package...' -ForegroundColor Cyan
Copy-Item .\app.py .\.publish\app.py -Force
Copy-Item .\requirements.txt .\.publish\requirements.txt -Force
if (Test-Path .\templates) { Copy-Item .\templates .\.publish\templates -Recurse -Force }
if (Test-Path .\static) { Copy-Item .\static .\.publish\static -Recurse -Force }
if (Test-Path .\app) { Copy-Item .\app .\.publish\app -Recurse -Force }

$zipPath = Join-Path $project 'deploy-package.zip'
if (Test-Path $zipPath) {
    Remove-Item $zipPath -Force
}
Compress-Archive -Path .\.publish\* -DestinationPath $zipPath -Force

Write-Host '[azure] Deploying code package...' -ForegroundColor Cyan
az webapp deployment source config-zip `
  --resource-group $ResourceGroup `
  --name $AppName `
  --src $zipPath | Out-Null

Remove-Item $zipPath -Force

$host = az webapp show --resource-group $ResourceGroup --name $AppName --query defaultHostName -o tsv
Write-Host "[azure] Deployment complete: https://$host" -ForegroundColor Green
