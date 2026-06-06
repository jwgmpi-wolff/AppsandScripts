# PowerShell script for deploying Azure infrastructure with Bicep
# This script deploys the AKS cluster, ACR, Storage Account, and Key Vault

param(
    [string]$ResourceGroupName = 'wolffmlrg',
    [string]$Location = 'westus2',
    [string]$SubscriptionId,
    [string]$BicepFilePath = 'bicep/main.bicep',
    [string]$ParametersFilePath = 'bicep/parameters.json'
)

Write-Host "Starting Azure infrastructure deployment..." -ForegroundColor Cyan

# Ensure required modules are loaded
Write-Host "Loading Azure modules..." -ForegroundColor Green
try {
    Import-Module Az.Resources -ErrorAction SilentlyContinue
    Import-Module Az.Accounts -ErrorAction SilentlyContinue
} catch {
    Write-Warning "Some modules may not be installed. Installing..."
    Install-Module -Name Az.Resources -Force -AllowClobber -ErrorAction SilentlyContinue
    Install-Module -Name Az.Accounts -Force -AllowClobber -ErrorAction SilentlyContinue
}

# Connect to Azure
Write-Host "Authenticating to Azure..." -ForegroundColor Green
try {
    # Try Managed Identity first (for Azure V
    Connect-AzAccount -Identity -ErrorAction Stop
} catch {
    # Fall back to interactive login
    Write-Host "Managed Identity not available. Using interactive login..." -ForegroundColor Yellow
    Connect-AzAccount
}

# Set subscription if provided
if ($SubscriptionId) {
    Set-AzContext -SubscriptionId $SubscriptionId
}

# Create resource group if it doesn't exist
Write-Host "Ensuring resource group exists: $ResourceGroupName" -ForegroundColor Green
$resourceGroup = Get-AzResourceGroup -Name $ResourceGroupName -ErrorAction SilentlyContinue
if (-not $resourceGroup) {
    New-AzResourceGroup -Name $ResourceGroupName -Location $Location
}

# Validate Bicep template
Write-Host "Validating Bicep template..." -ForegroundColor Green
$validationResult = Test-AzResourceGroupDeployment `
    -ResourceGroupName $ResourceGroupName `
    -TemplateFile $BicepFilePath `
    -TemplateParameterFile $ParametersFilePath

if ($validationResult.Log) {
    Write-Host "Validation complete. Details:" -ForegroundColor Green
    $validationResult.Log | Out-String
}

# Deploy infrastructure
Write-Host "Deploying infrastructure... This may take 10-15 minutes." -ForegroundColor Green
$deployment = New-AzResourceGroupDeployment `
    -ResourceGroupName $ResourceGroupName `
    -TemplateFile $BicepFilePath `
    -TemplateParameterFile $ParametersFilePath `
    -Verbose

Write-Host "Deployment completed successfully!" -ForegroundColor Green

# Output deployment outputs
Write-Host "Deployment Outputs:" -ForegroundColor Cyan
$deployment.Outputs | ForEach-Object {
    Write-Host "$($_.Key): $($_.Value.Value)"
}

Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "1. Run: .\scripts\deploy-to-aks.sh"
Write-Host "2. Configure Key Vault secrets in Azure Portal"
Write-Host "3. Monitor deployment with: kubectl logs -f deployment/workbook-audit-deployment -n workbook-audit"
