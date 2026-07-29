[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-zA-Z0-9._()-]+$')]
    [string] $ResourceGroup,

    [Parameter(Mandatory)]
    [string] $Location,

    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9-]{2,60}$')]
    [string] $AppName,

    [ValidateSet('B1', 'B2', 'B3', 'P0V3', 'P1V3')]
    [string] $Sku = 'B1',

    [string] $VnetName = 'vnet-number-origin-desk',

    [string] $EntraAppDisplayName
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    throw 'Azure CLI is required. Install it and run az login before deploying.'
}

$account = az account show --output json | ConvertFrom-Json
if (-not $account.id -or -not $account.tenantId) {
    throw 'No active Azure CLI session. Run az login and select a subscription.'
}

$subscriptionId = $account.id
$tenantId = $account.tenantId
$displayName = if ($EntraAppDisplayName) { $EntraAppDisplayName } else { "$AppName App Service" }
$callbackUrl = "https://$AppName.azurewebsites.net/.auth/login/aad/callback"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $repositoryRoot 'web'
$stagingRoot = Join-Path $PSScriptRoot '.deploy-staging'
$zipPath = Join-Path $PSScriptRoot "$AppName.zip"
$parameterPath = Join-Path $PSScriptRoot '.secure-deployment-parameters.json'
$templatePath = Join-Path $PSScriptRoot 'azuredeploy.json'

if (-not (Test-Path (Join-Path $webRoot 'package-lock.json'))) {
    throw 'web/package-lock.json is missing. Run npm install in web before deploying.'
}

try {
    Remove-Item $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $zipPath, $parameterPath -Force -ErrorAction SilentlyContinue
    New-Item $stagingRoot -ItemType Directory | Out-Null

    Copy-Item (Join-Path $webRoot 'package.json') $stagingRoot
    Copy-Item (Join-Path $webRoot 'package-lock.json') $stagingRoot
    Copy-Item (Join-Path $webRoot 'src') $stagingRoot -Recurse
    Copy-Item (Join-Path $webRoot 'public') $stagingRoot -Recurse
    Compress-Archive -Path (Join-Path $stagingRoot '*') -DestinationPath $zipPath -CompressionLevel Optimal

    az group create --name $ResourceGroup --location $Location --output none

    $clientId = az ad app list --display-name $displayName --query "[?signInAudience=='AzureADMyOrg'].appId | [0]" --output tsv
    if (-not $clientId) {
        $clientId = az ad app create `
            --display-name $displayName `
            --sign-in-audience AzureADMyOrg `
            --web-redirect-uris $callbackUrl `
            --query appId `
            --output tsv
    }
    else {
        az ad app update --id $clientId --web-redirect-uris $callbackUrl --output none
    }

    az ad sp show --id $clientId --output none 2>$null
    if ($LASTEXITCODE -ne 0) {
        az ad sp create --id $clientId --output none
    }

    $credentialEndDate = (Get-Date).ToUniversalTime().AddYears(1).ToString('yyyy-MM-dd')
    $clientSecret = az ad app credential reset `
        --id $clientId `
        --append `
        --display-name 'App Service Easy Auth' `
        --end-date $credentialEndDate `
        --query password `
        --output tsv
    if (-not $clientSecret) {
        throw 'The Entra application credential could not be created.'
    }

    $parameters = @{
        '$schema' = 'https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#'
        contentVersion = '1.0.0.0'
        parameters = @{
            siteName = @{ value = $AppName }
            sku = @{ value = $Sku }
            tenantId = @{ value = $tenantId }
            entraClientId = @{ value = $clientId }
            entraClientSecret = @{ value = $clientSecret }
            vnetName = @{ value = $VnetName }
            publicNetworkAccess = @{ value = 'Enabled' }
        }
    }
    $parameters | ConvertTo-Json -Depth 8 | Set-Content $parameterPath -Encoding utf8
    $clientSecret = $null

    az deployment group create `
        --name "number-origin-desk-$(Get-Date -Format 'yyyyMMddHHmmss')" `
        --resource-group $ResourceGroup `
        --template-file $templatePath `
        --parameters "@$parameterPath" `
        --output none

    az webapp deployment source config-zip `
        --resource-group $ResourceGroup `
        --name $AppName `
        --src $zipPath `
        --output none

    az webapp update `
        --resource-group $ResourceGroup `
        --name $AppName `
        --set publicNetworkAccess=Disabled `
        --output none

    Write-Host "Deployment complete: https://$AppName.azurewebsites.net"
    Write-Host "Tenant authentication: required ($tenantId)"
    Write-Host 'Public network access: disabled'
    Write-Host 'Connect through the VNet (VPN, ExpressRoute, or a VNet-hosted client) before browsing the URL.'
    Write-Host "Private DNS must resolve $AppName.azurewebsites.net through privatelink.azurewebsites.net."
    Write-Host "Credential expires: $credentialEndDate; rotate before expiration."
}
finally {
    Remove-Item $stagingRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $zipPath, $parameterPath -Force -ErrorAction SilentlyContinue
}