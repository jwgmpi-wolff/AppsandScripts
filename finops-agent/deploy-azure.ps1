param(
    [string]$SubscriptionId,
    [string]$ResourceGroup = 'rg-finops-agent',
    [string]$Location = 'eastus',
    [string]$EnvironmentName = 'prod',
    [string]$ServiceName = 'api',
    [string]$FunctionAppName = '',
    [string]$StorageName = '',
    [string]$AppServicePlanName = '',
    [string]$AppInsightsName = ''
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Get-RandomSuffix {
    -join ((97..122) + (48..57) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
}

if (-not $FunctionAppName) {
    $FunctionAppName = "func-finops-$(Get-RandomSuffix)"
}
if (-not $StorageName) {
    $StorageName = ("stfinops$(Get-RandomSuffix)").ToLower()
}
if (-not $AppServicePlanName) {
    $AppServicePlanName = "asp-finops-$EnvironmentName"
}
if (-not $AppInsightsName) {
    $AppInsightsName = "appi-finops-$EnvironmentName"
}

Write-Host 'Ensuring Azure CLI login...'
$null = az account show 2>$null
if ($LASTEXITCODE -ne 0) {
    az login | Out-Null
}

if ($SubscriptionId) {
    az account set --subscription $SubscriptionId
}

Write-Host "Using subscription: $(az account show --query id -o tsv)"

Write-Host "Creating resource group $ResourceGroup in $Location..."
az group create --name $ResourceGroup --location $Location --output none

Write-Host 'Deploying infrastructure template...'
az deployment group create `
    --resource-group $ResourceGroup `
    --template-file .\infra\resources.bicep `
    --parameters location=$Location environmentName=$EnvironmentName serviceName=$ServiceName appServicePlanName=$AppServicePlanName appInsightsName=$AppInsightsName functionAppName=$FunctionAppName storageName=$StorageName `
    --output none

Write-Host 'Packaging source for zip deploy...'
$zipPath = Join-Path $PSScriptRoot 'finops-agent-source.zip'
if (Test-Path $zipPath) {
    Remove-Item $zipPath -Force
}
Compress-Archive -Path .\function_app.py, .\host.json, .\requirements.txt -DestinationPath $zipPath -Force

Write-Host 'Deploying function code with remote build...'
az functionapp deployment source config-zip `
    --resource-group $ResourceGroup `
    --name $FunctionAppName `
    --src $zipPath `
    --build-remote true `
    --output none

az functionapp restart --resource-group $ResourceGroup --name $FunctionAppName --output none

$key = az functionapp keys list --resource-group $ResourceGroup --name $FunctionAppName --query functionKeys.default -o tsv
$host = az functionapp show --resource-group $ResourceGroup --name $FunctionAppName --query defaultHostName -o tsv

Write-Host ''
Write-Host 'Deployment complete.'
Write-Host "Function app: $FunctionAppName"
Write-Host "Resource group: $ResourceGroup"
Write-Host "Health URL: https://$host/api/finops_health?code=$key"
Write-Host "Report URL: https://$host/api/run_finops_report?format=html&pageSize=25&code=$key"
