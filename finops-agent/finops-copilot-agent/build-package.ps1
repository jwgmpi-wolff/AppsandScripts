[CmdletBinding()]
param(
    [ValidateSet("dev", "local")]
    [string]$Environment = "dev"
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$sourcePath = Join-Path $projectRoot "appPackage"
$buildPath = Join-Path $sourcePath "build"
$stagePath = Join-Path $buildPath "staging.$Environment"
$zipPath = Join-Path $buildPath "appPackage.$Environment.zip"
$environmentPath = Join-Path $projectRoot "env/.env.$Environment"

if (-not (Test-Path $environmentPath)) {
    throw "Environment file not found: $environmentPath"
}

$variables = @{}
Get-Content $environmentPath | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $name, $value = $line.Split("=", 2)
        $variables[$name.Trim()] = $value.Trim()
    }
}

foreach ($requiredVariable in @(
    "TEAMS_APP_ID",
    "APP_NAME_SUFFIX",
    "AZUREMANAGEMENTOAUTH_REGISTRATION_ID",
    "DEPLOYMENT_TENANT_ID",
    "ENTRA_CLIENT_ID",
    "API_BASE_URL",
    "API_HOST",
    "PUBLISHER_NAME",
    "PUBLISHER_WEBSITE_URL",
    "PRIVACY_URL",
    "TERMS_OF_USE_URL",
    "CONTACT_EMAIL"
)) {
    if (-not $variables.ContainsKey($requiredVariable) -or [string]::IsNullOrWhiteSpace($variables[$requiredVariable])) {
        throw "Required variable '$requiredVariable' is missing from $environmentPath"
    }
}

Remove-Item $stagePath -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
New-Item $stagePath -ItemType Directory -Force | Out-Null

foreach ($asset in @(
    "adaptiveCards",
    "apiSpecificationFile/openapi.yaml",
    "ai-plugin.json",
    "color.png",
    "declarativeAgent.json",
    "instruction.txt",
    "manifest.json",
    "outline.png"
)) {
    $assetPath = Join-Path $sourcePath $asset
    if (-not (Test-Path $assetPath)) {
        throw "Required package asset not found: $assetPath"
    }

    $destination = Join-Path $stagePath $asset
    $destinationParent = Split-Path $destination -Parent
    New-Item $destinationParent -ItemType Directory -Force | Out-Null
    Copy-Item $assetPath -Destination $destination -Recurse -Force
}

Get-ChildItem $stagePath -File -Recurse | Where-Object Extension -in ".json", ".yaml", ".yml", ".txt" | ForEach-Object {
    $content = Get-Content $_.FullName -Raw
    foreach ($variable in $variables.GetEnumerator()) {
        $content = $content.Replace('${{' + $variable.Key + '}}', $variable.Value)
    }
    Set-Content $_.FullName $content -NoNewline -Encoding utf8
}

$declarativeAgentPath = Join-Path $stagePath "declarativeAgent.json"
$declarativeAgent = Get-Content $declarativeAgentPath -Raw | ConvertFrom-Json
$declarativeAgent.instructions = Get-Content (Join-Path $sourcePath "instruction.txt") -Raw
$declarativeAgent | ConvertTo-Json -Depth 100 | Set-Content $declarativeAgentPath -Encoding utf8
Remove-Item (Join-Path $stagePath "instruction.txt") -Force

$unresolvedTokens = Get-ChildItem $stagePath -File -Recurse |
    Select-String -Pattern '\$\{\{|\$\[file\(' -List
if ($unresolvedTokens) {
    $files = ($unresolvedTokens.Path | Sort-Object -Unique) -join ", "
    throw "Package contains unresolved template tokens: $files"
}

Compress-Archive -Path (Join-Path $stagePath "*") -DestinationPath $zipPath -CompressionLevel Optimal
Remove-Item $stagePath -Recurse -Force

Write-Host "Created $zipPath"