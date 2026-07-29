param(
  [string]$SubscriptionId = "9ea136a4-ccfd-45c2-a8e5-01a38c0563d3",
  [string]$ResourceGroup = "rg-stockstreamportfolio",
  [string]$Location = "eastus",
  [string]$AppName = "stockstreamportfolio",
  [string]$AdminUpn = "admin@wolffentp.net",
  [string]$MarketProviderName = "PlaceholderProvider",
  [string]$MarketProviderBaseUrl = "https://example.invalid/",
  [string]$MarketProviderApiKeySecretValue = "placeholder-not-live",
  [switch]$SkipDeploy
)

$ErrorActionPreference = "Stop"

function Ensure-AppRegistration {
  param(
    [Parameter(Mandatory = $true)][string]$DisplayName,
    [switch]$PublicClient
  )

  $appId = az ad app list --display-name $DisplayName --query "[0].appId" -o tsv
  if ([string]::IsNullOrWhiteSpace($appId)) {
    if ($PublicClient) {
      $appId = az ad app create --display-name $DisplayName --sign-in-audience AzureADMyOrg --is-fallback-public-client true --query appId -o tsv
    } else {
      $appId = az ad app create --display-name $DisplayName --sign-in-audience AzureADMyOrg --query appId -o tsv
    }
  }

  $null = az ad sp create --id $appId 2>$null
  return $appId
}

function Ensure-AppRoleAssignment {
  param(
    [Parameter(Mandatory = $true)][string]$UserObjectId,
    [Parameter(Mandatory = $true)][string]$ResourceSpObjectId,
    [Parameter(Mandatory = $true)][string]$RoleId
  )

  $existing = az rest --method GET --url "https://graph.microsoft.com/v1.0/users/$UserObjectId/appRoleAssignments" --query "value[?resourceId=='$ResourceSpObjectId' && appRoleId=='$RoleId'] | length(@)" -o tsv
  if ($existing -eq "0") {
    $body = @{
      principalId = $UserObjectId
      resourceId = $ResourceSpObjectId
      appRoleId = $RoleId
    } | ConvertTo-Json
    $tmpBodyFile = [System.IO.Path]::GetTempFileName()
    try {
      Set-Content -Path $tmpBodyFile -Value $body -Encoding utf8
      az rest --method POST --url "https://graph.microsoft.com/v1.0/users/$UserObjectId/appRoleAssignments" --headers "Content-Type=application/json" --body "@$tmpBodyFile" | Out-Null
    } finally {
      Remove-Item -Path $tmpBodyFile -ErrorAction SilentlyContinue
    }
  }
}

Write-Host "Using subscription $SubscriptionId"
az account set --subscription $SubscriptionId

$tenantId = az account show --query tenantId -o tsv

$backendDisplayName = "StockStreamPortfolio-Backend-API"
$androidDisplayName = "StockStreamPortfolio-Android"

Write-Host "Ensuring app registrations"
$backendAppId = Ensure-AppRegistration -DisplayName $backendDisplayName
$androidAppId = Ensure-AppRegistration -DisplayName $androidDisplayName -PublicClient

$backendAppObjectId = az ad app show --id $backendAppId --query id -o tsv
$backendSpObjectId = az ad sp show --id $backendAppId --query id -o tsv

$backendApp = az ad app show --id $backendAppId | ConvertFrom-Json
$existingScope = $backendApp.api.oauth2PermissionScopes | Where-Object { $_.value -eq "access_as_user" } | Select-Object -First 1
$scopeId = if ($null -ne $existingScope) { $existingScope.id } else { [guid]::NewGuid().ToString() }

$existingAdminRole = $backendApp.appRoles | Where-Object { $_.value -eq "Admin" } | Select-Object -First 1
$adminRoleId = if ($null -ne $existingAdminRole) { $existingAdminRole.id } else { [guid]::NewGuid().ToString() }

$existingUserRole = $backendApp.appRoles | Where-Object { $_.value -eq "User" } | Select-Object -First 1
$userRoleId = if ($null -ne $existingUserRole) { $existingUserRole.id } else { [guid]::NewGuid().ToString() }

$backendPatch = @{
  identifierUris = @("api://$backendAppId")
  api = @{
    oauth2PermissionScopes = @(
      @{
        id = $scopeId
        adminConsentDisplayName = "Access StockStreamPortfolio API"
        adminConsentDescription = "Allow mobile app to access StockStreamPortfolio API as the signed-in user."
        userConsentDisplayName = "Access StockStreamPortfolio API"
        userConsentDescription = "Allow this app to access StockStreamPortfolio API on your behalf."
        isEnabled = $true
        type = "User"
        value = "access_as_user"
      }
    )
  }
  appRoles = @(
    @{
      allowedMemberTypes = @("User")
      description = "Administrators of StockStreamPortfolio."
      displayName = "Admin"
      id = $adminRoleId
      isEnabled = $true
      value = "Admin"
      origin = "Application"
    },
    @{
      allowedMemberTypes = @("User")
      description = "Standard users of StockStreamPortfolio."
      displayName = "User"
      id = $userRoleId
      isEnabled = $true
      value = "User"
      origin = "Application"
    }
  )
}

$androidPatch = @{
  publicClient = @{
    redirectUris = @(
      "msauth://net.wolffentp.stockstreamportfolio/Pu7mHp1uF2ESAx7CzShpWsi3YoA",
      "msauth://net.wolffentp.stockstreamportfolio/qWy33M8DAxbDTet_HGBPaLbJO8w"
    )
  }
}

$backendPatchJson = $backendPatch | ConvertTo-Json -Depth 20
$tmpPatchFile = [System.IO.Path]::GetTempFileName()
try {
  Set-Content -Path $tmpPatchFile -Value $backendPatchJson -Encoding utf8
  az rest --method PATCH --url "https://graph.microsoft.com/v1.0/applications/$backendAppObjectId" --headers "Content-Type=application/json" --body "@$tmpPatchFile" | Out-Null
} finally {
  Remove-Item -Path $tmpPatchFile -ErrorAction SilentlyContinue
}

$androidPatchJson = $androidPatch | ConvertTo-Json -Depth 10
$tmpAndroidPatchFile = [System.IO.Path]::GetTempFileName()
try {
  Set-Content -Path $tmpAndroidPatchFile -Value $androidPatchJson -Encoding utf8
  $androidAppObjectId = az ad app show --id $androidAppId --query id -o tsv
  az rest --method PATCH --url "https://graph.microsoft.com/v1.0/applications/$androidAppObjectId" --headers "Content-Type=application/json" --body "@$tmpAndroidPatchFile" | Out-Null
} finally {
  Remove-Item -Path $tmpAndroidPatchFile -ErrorAction SilentlyContinue
}

Write-Host "Granting Android delegated permission and admin consent"
az ad app permission add --id $androidAppId --api $backendAppId --api-permissions "$scopeId=Scope" | Out-Null
az ad app permission grant --id $androidAppId --api $backendAppId --scope "access_as_user" | Out-Null
az ad app permission admin-consent --id $androidAppId | Out-Null

Write-Host "Assigning Admin and User app roles to $AdminUpn"
$userObjectId = az ad user show --id $AdminUpn --query id -o tsv
Ensure-AppRoleAssignment -UserObjectId $userObjectId -ResourceSpObjectId $backendSpObjectId -RoleId $adminRoleId
Ensure-AppRoleAssignment -UserObjectId $userObjectId -ResourceSpObjectId $backendSpObjectId -RoleId $userRoleId

if (-not $SkipDeploy) {
  Write-Host "Deploying Azure resources and backend app"
  & "$PSScriptRoot\deploy.ps1" `
    -SubscriptionId $SubscriptionId `
    -ResourceGroup $ResourceGroup `
    -Location $Location `
    -AppName $AppName `
    -TenantId $tenantId `
    -BackendApiClientId $backendAppId `
    -BackendApiAudience "api://$backendAppId" `
    -MarketProviderName $MarketProviderName `
    -MarketProviderBaseUrl $MarketProviderBaseUrl `
    -MarketProviderApiKeySecretValue $MarketProviderApiKeySecretValue
}

$result = @{
  subscriptionId = $SubscriptionId
  tenantId = $tenantId
  backendApiClientId = $backendAppId
  androidClientId = $androidAppId
  backendScope = "api://$backendAppId/access_as_user"
  resourceGroup = $ResourceGroup
  appName = $AppName
}

$result | ConvertTo-Json -Depth 5
