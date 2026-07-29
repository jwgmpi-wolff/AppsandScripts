param(
  [Parameter(Mandatory = $true)] [string]$SubscriptionId,
  [Parameter(Mandatory = $true)] [string]$ResourceGroup,
  [Parameter(Mandatory = $true)] [string]$Location,
  [Parameter(Mandatory = $true)] [string]$AppName,
  [Parameter(Mandatory = $true)] [string]$TenantId,
  [Parameter(Mandatory = $true)] [string]$BackendApiClientId,
  [Parameter(Mandatory = $true)] [string]$BackendApiAudience,
  [Parameter(Mandatory = $true)] [string]$MarketProviderName,
  [Parameter(Mandatory = $true)] [string]$MarketProviderBaseUrl,
  [Parameter(Mandatory = $true)] [string]$MarketProviderApiKeySecretValue
)

$ErrorActionPreference = "Stop"

Write-Host "Setting Azure subscription context"
az account set --subscription $SubscriptionId

Write-Host "Creating resource group if missing"
az group create --name $ResourceGroup --location $Location | Out-Null

Write-Host "Deploying Bicep infrastructure"
$deployment = az deployment group create `
  --resource-group $ResourceGroup `
  --template-file "$(Resolve-Path "$PSScriptRoot\..\bicep\main.bicep")" `
  --parameters appName=$AppName tenantId=$TenantId backendApiClientId=$BackendApiClientId backendApiAudience=$BackendApiAudience location=$Location `
  --query properties.outputs -o json | ConvertFrom-Json

$keyVaultUri = $deployment.keyVaultUri.value
$webAppName = $deployment.webAppName.value

$keyVaultHost = ([Uri]$keyVaultUri).Host
$keyVaultName = $keyVaultHost.Split('.')[0]

Write-Host "Storing provider API key in Key Vault"
try {
  az keyvault secret set --vault-name $keyVaultName --name "market-provider-api-key" --value $MarketProviderApiKeySecretValue | Out-Null
}
catch {
  Write-Warning "Key Vault write failed. Continuing with direct app-setting API key fallback."
}

Write-Host "Setting provider app settings"
az webapp config appsettings set --resource-group $ResourceGroup --name $webAppName --settings `
  "STOCKSTREAM_MarketDataProvider__ProviderName=$MarketProviderName" `
  "STOCKSTREAM_MarketDataProvider__BaseUrl=$MarketProviderBaseUrl" `
  "STOCKSTREAM_MarketDataProvider__ApiKey=$MarketProviderApiKeySecretValue" `
  "STOCKSTREAM_MarketDataProvider__ApiKeySecretName=market-provider-api-key" `
  "MarketDataProvider__ProviderName=$MarketProviderName" `
  "MarketDataProvider__BaseUrl=$MarketProviderBaseUrl" `
  "MarketDataProvider__ApiKey=$MarketProviderApiKeySecretValue" | Out-Null

Write-Host "Publishing backend"
Push-Location "$PSScriptRoot\..\..\backend\src\StockStreamPortfolio.Api"
dotnet publish -c Release -o publish
$zipPath = Join-Path (Get-Location) "publish.zip"
if (Test-Path $zipPath) {
  Remove-Item $zipPath -Force
}
Compress-Archive -Path "publish\*" -DestinationPath $zipPath -Force
az webapp deploy --resource-group $ResourceGroup --name $webAppName --src-path $zipPath --type zip
Pop-Location

Write-Host "Deployment complete."
Write-Host "Web App Name: $webAppName"
Write-Host "Key Vault URI: $keyVaultUri"
