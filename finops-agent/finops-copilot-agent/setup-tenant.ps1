[CmdletBinding()]
param(
    [string]$TenantId,
    [Parameter(Mandatory)]
    [string]$ApiBaseUrl,
    [Parameter(Mandatory)]
    [string]$EntraClientId,
    [Parameter(Mandatory)]
    [string]$OAuthRegistrationId,
    [string]$TeamsAppId = [guid]::NewGuid().Guid,
    [string]$PublisherName,
    [string]$PublisherWebsiteUrl,
    [string]$PrivacyUrl,
    [string]$TermsOfUseUrl,
    [string]$ContactEmail,
    [ValidateSet("dev", "local")]
    [string]$Environment = "dev",
    [switch]$SkipPackage,
    [switch]$Publish
)

$ErrorActionPreference = "Stop"

function Get-EnvironmentValue {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [string]$Name
    )

    $prefix = "$Name="
    $line = Get-Content -Path $Path | Where-Object { $_.StartsWith($prefix) } | Select-Object -Last 1
    if ($line) {
        return $line.Substring($prefix.Length).Trim()
    }
    return $null
}

if ($SkipPackage -and $Publish) {
    throw "Publish cannot be combined with SkipPackage."
}

if (-not $TenantId) {
    if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
        throw "TenantId was not supplied and Azure CLI is unavailable. Pass -TenantId explicitly."
    }

    $account = az account show --output json 2>$null | ConvertFrom-Json
    if (-not $account.tenantId) {
        throw "No authenticated Azure account was found. Run 'az login' or pass -TenantId explicitly."
    }

    $TenantId = $account.tenantId
    if (-not $ContactEmail -and $account.user.name) {
        $ContactEmail = $account.user.name
    }
}

foreach ($guidValue in @{
    TenantId = $TenantId
    EntraClientId = $EntraClientId
    TeamsAppId = $TeamsAppId
}.GetEnumerator()) {
    $parsedGuid = [guid]::Empty
    if (-not [guid]::TryParse($guidValue.Value, [ref]$parsedGuid)) {
        throw "$($guidValue.Key) must be a valid GUID."
    }
}

$apiUri = [uri]$ApiBaseUrl
if ($apiUri.Scheme -ne "https" -or -not $apiUri.Host) {
    throw "ApiBaseUrl must be a public HTTPS URL."
}

$normalizedApiBaseUrl = $ApiBaseUrl.TrimEnd("/")
$PublisherName = if ($PublisherName) { $PublisherName } else { "FinOps CCoE" }
$PublisherWebsiteUrl = if ($PublisherWebsiteUrl) { $PublisherWebsiteUrl } else { "$normalizedApiBaseUrl/" }
$PrivacyUrl = if ($PrivacyUrl) { $PrivacyUrl } else { $PublisherWebsiteUrl }
$TermsOfUseUrl = if ($TermsOfUseUrl) { $TermsOfUseUrl } else { $PublisherWebsiteUrl }

if (-not $ContactEmail) {
    throw "ContactEmail could not be inferred. Pass -ContactEmail explicitly."
}

$environmentPath = Join-Path $PSScriptRoot "env/.env.$Environment"
$environmentContent = @"
TEAMSFX_ENV=$Environment
APP_NAME_SUFFIX=$Environment
AGENT_SCOPE=personal
DEPLOYMENT_TENANT_ID=$TenantId
TEAMS_APP_ID=$TeamsAppId
ENTRA_CLIENT_ID=$EntraClientId
AZUREMANAGEMENTOAUTH_REGISTRATION_ID=$OAuthRegistrationId
API_BASE_URL=$normalizedApiBaseUrl
API_HOST=$($apiUri.Host)
PUBLISHER_NAME=$PublisherName
PUBLISHER_WEBSITE_URL=$PublisherWebsiteUrl
PRIVACY_URL=$PrivacyUrl
TERMS_OF_USE_URL=$TermsOfUseUrl
CONTACT_EMAIL=$ContactEmail
AZURE_AI_OPENAI_ENDPOINT=
AZURE_AI_API_KEY=
AZURE_AI_API_VERSION=
AZURE_AI_MODEL_NAME=
"@

Set-Content -Path $environmentPath -Value $environmentContent -Encoding utf8

Write-Host "Created $environmentPath"
Write-Host "Tenant: $TenantId"
Write-Host "Teams app ID: $TeamsAppId"
Write-Host "API: $normalizedApiBaseUrl"
Write-Host "No client secret was written."

if (-not $SkipPackage) {
    & (Join-Path $PSScriptRoot "build-package.ps1") -Environment $Environment
    $packagePath = Join-Path $PSScriptRoot "appPackage/build/appPackage.$Environment.zip"

    if (-not (Get-Command atk -ErrorAction SilentlyContinue)) {
        throw "Microsoft 365 Agents Toolkit CLI was not found. Install it with 'npm install -g @microsoft/m365agentstoolkit-cli@beta'."
    }

    $env:ATK_CLI_SKILL = "true"
    & atk validate --package-file $packagePath --validate-method validation-rules
    if ($LASTEXITCODE -ne 0) {
        throw "Agent package validation failed."
    }

    Write-Host "Package ready: appPackage/build/appPackage.$Environment.zip"

    if ($Publish) {
        & atk provision --env $Environment -i false
        if ($LASTEXITCODE -ne 0) {
            throw "Toolkit provisioning failed. The validated ZIP is still ready for Teams Admin Center upload."
        }

        $teamsAppTenantId = Get-EnvironmentValue -Path $environmentPath -Name "TEAMS_APP_TENANT_ID"
        if (-not $teamsAppTenantId) {
            throw "Toolkit provisioning did not report TEAMS_APP_TENANT_ID. Confirm the Microsoft 365 account belongs to tenant $TenantId before publishing."
        }
        if ($teamsAppTenantId -ne $TenantId) {
            throw "Microsoft 365 tenant mismatch. Requested $TenantId but Toolkit provisioned in $teamsAppTenantId. Run 'atk auth logout m365', sign in to the requested tenant, and retry."
        }

        $m365AppId = Get-EnvironmentValue -Path $environmentPath -Name "M365_APP_ID"
        if (-not $m365AppId) {
            throw "Toolkit provisioning did not extend the app to Microsoft 365 or write M365_APP_ID."
        }

        $m365TitleId = Get-EnvironmentValue -Path $environmentPath -Name "M365_TITLE_ID"
        if (-not $m365TitleId) {
            throw "Toolkit provisioning did not extend the app to Microsoft 365 or write M365_TITLE_ID."
        }

        # Provisioning creates the Developer Portal app and may replace TEAMS_APP_ID.
        & (Join-Path $PSScriptRoot "build-package.ps1") -Environment $Environment
        & atk validate --package-file $packagePath --validate-method validation-rules
        if ($LASTEXITCODE -ne 0) {
            throw "The provisioned package failed validation."
        }

        & atk publish --env $Environment -i false
        if ($LASTEXITCODE -ne 0) {
            throw "Toolkit publication failed. The validated ZIP is still ready for Teams Admin Center upload."
        }
    }
}