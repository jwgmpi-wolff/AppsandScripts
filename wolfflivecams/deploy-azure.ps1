param(
    [Parameter(Mandatory = $true)][string]$ResourceGroup,
    [Parameter(Mandatory = $true)][string]$Location,
    [Parameter(Mandatory = $true)][string]$AppName,
    [string]$PlanName = "${AppName}-plan",
    [string]$Sku = "B1"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host "Ensuring Azure CLI is available..."
az version | Out-Null

Write-Host "Creating/Updating resource group..."
az group create --name $ResourceGroup --location $Location | Out-Null

Write-Host "Deploying infrastructure template..."
az deployment group create `
  --resource-group $ResourceGroup `
  --template-file .\infra\azuredeploy.json `
  --parameters siteName=$AppName serverFarmName=$PlanName location=$Location skuName=$Sku | Out-Null

Write-Host "Preparing deployment package..."
if (Test-Path .publish) {
    Remove-Item -Recurse -Force .publish
}
New-Item -ItemType Directory -Path .publish | Out-Null

Copy-Item .\app -Destination .\.publish\app -Recurse -Force
Copy-Item .\requirements.txt -Destination .\.publish\requirements.txt -Force

$zipPath = Join-Path $root "app.zip"
if (Test-Path $zipPath) {
    Remove-Item -Force $zipPath
}
Compress-Archive -Path .\.publish\* -DestinationPath $zipPath

Write-Host "Deploying code to Azure Web App..."
az webapp deployment source config-zip `
  --resource-group $ResourceGroup `
  --name $AppName `
  --src $zipPath | Out-Null

$webAppUrl = "https://$AppName.azurewebsites.net"
Write-Host "Deployment complete: $webAppUrl"
