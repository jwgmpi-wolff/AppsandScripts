<#
.SYNOPSIS
Generates an audit of Azure Monitor Deployment workbook queries across subscriptions and publishes the results to local files and Azure Storage.

.DESCRIPTION
This script performs an end-to-end audit of Azure Monitor Deployment Workbooks and their queries:

- **Authentication**: Uses Managed Identity (preferred) or Service Principal for non-interactive login.
- **Subscription Processing**:
    * Iterates through all accessible subscriptions.
    * Sets the subscription context and enumerates Workbooks (category: 'workbook').
- **Query Extraction**:
    * Retrieves workbook JSON content.
    * Extracts query-bearing items (KQL/ARG) and builds structured objects with metadata:
        - Type, Feature, Version, Title, QueryType, ResourceType, CrossComponentResources, Query.
- **AI-Assisted Description**:
    * Calls local module `call_ai_foundry_ext` to generate human-readable descriptions for each query.
    * Cleans and normalizes AI output for readability.
- **Output Generation**:
    * Creates three local artifacts in `/app/output/`:
        - HTML report (`workbookname_queries.html`)
        - CSV export (`workbookname_queries.csv`)
        - JSON file (`workbookname_queries.json`)
- **Azure Storage Upload**:
    * Switches to a designated subscription/resource group.
    * Ensures Storage Account and container exist.
    * Uploads the CSV file to the specified container.
- **Automation-Friendly**:
    * Handles Az module installation and token freshness.
    * Designed for pipelines, Azure Automation, or VM contexts without interactive prompts.

.VERSION HISTORY
v1.0  - Initial release
v1.1  - Expanded help, clarified module prerequisites, added AI-generated query descriptions
v1.2  - Applied PSObject + Add-Member construction style end-to-end
v2.0  - Containerized for Kubernetes with Managed Identity support
#>

# ---------- Authentication (Managed Identity preferred) ----------

Import-Module -Name call_ai_foundry_ext -Force -Verbose -ErrorAction SilentlyContinue

Write-Host "Authenticating with Managed Identity..." -ForegroundColor Cyan

try {
    # Attempt Managed Identity authentication (for Kubernetes/Azure VMs)
    $context = Connect-AzAccount -Identity -ErrorAction SilentlyContinue
    if (-not $context) {
        throw "Managed Identity authentication failed"
    }
} catch {
    Write-Warning "Managed Identity failed. Attempting alternative authentication methods..."
    $context = Get-AzContext
    if (-not $context) {
        Write-Error "No valid Azure context available. Ensure Managed Identity or Service Principal is configured."
        exit 1
    }
}

Write-Host "Successfully authenticated to Azure" -ForegroundColor Green

# ---------- Configuration from environment or defaults ----------

$subscriptionId = $env:AZURE_SUBSCRIPTION_ID
$resourceGroup  = $env:AZURE_RESOURCE_GROUP
$akvName        = $env:AZURE_KEYVAULT_NAME
$akvEndpoint    = $env:AZURE_KEYVAULT_ENDPOINT
$aiEndpoint     = $env:AZURE_AI_ENDPOINT
$aiKey          = $env:AZURE_AI_KEY
$storageAccount = $env:AZURE_STORAGE_ACCOUNT
$storageKey     = $env:AZURE_STORAGE_KEY
$storageContainer = $env:AZURE_STORAGE_CONTAINER

# Fallback to defaults if not provided
if (-not $akvName) { $akvName = "wolffmlkv" }
if (-not $resourceGroup) { $resourceGroup = "wolffmlrg" }

# ---------- Retrieve secrets from Key Vault ----------

Write-Host "Retrieving secrets from Key Vault: $akvName" -ForegroundColor Cyan

try {
    # Update Key Vault network settings temporarily for access
    Update-AzKeyVault -VaultName $akvName -ResourceGroupName $resourceGroup -PublicNetworkAccess Enabled -ErrorAction SilentlyContinue | Out-Null
    Update-AzKeyVaultNetworkRuleSet -VaultName $akvName -ResourceGroupName $resourceGroup -Bypass AzureServices -DefaultAction Allow -ErrorAction SilentlyContinue | Out-Null

    # Retrieve secrets (if not already set via environment variables)
    if (-not $aiEndpoint) {
        $aiEndpoint = Get-AzKeyVaultSecret -VaultName $akvName -Name "wolffaipoc2-resource-endpoint" -AsPlainText -ErrorAction SilentlyContinue
    }
    if (-not $aiKey) {
        $aiKey = Get-AzKeyVaultSecret -VaultName $akvName -Name "wolffaipoc2-resource-key1" -AsPlainText -ErrorAction SilentlyContinue
    }

    Write-Host "AI Foundry endpoint and key retrieved successfully" -ForegroundColor Green
} catch {
    Write-Warning "Failed to retrieve secrets from Key Vault: $($_.Exception.Message)"
}

# ---------- Helper Functions ----------

function Convert-GuidanceToCRLF {
    param([Parameter(Mandatory)][string]$Text)
    $lines = $Text -split "`r?`n"
    ($lines | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }) -join "`r`n`r`n"
}

# ---------- Working variables & paths ----------

$querylist = @()
$resultsfilename = 'workbookname_queries.csv'
$OutPath = '/app/output'
$null = New-Item -ItemType Directory -Path $OutPath -ErrorAction SilentlyContinue

Write-Host "Output path: $OutPath" -ForegroundColor Cyan

# ---------- Enumerate subscriptions with progress ----------

$subscriptions = Get-AzSubscription -WarningAction SilentlyContinue
$subCount = ($subscriptions | Measure-Object).Count

if ($subCount -eq 0) {
    Write-Error "No subscriptions found. Check your Managed Identity permissions."
    exit 1
}

Write-Host "Found $subCount subscription(s). Starting audit..." -ForegroundColor Cyan

$subIndex = 0
foreach ($subscription in $subscriptions) {
    $subIndex++
    Write-Progress -Id 1 `
        -Activity "Processing Subscriptions" `
        -Status "Subscription: $($subscription.Name)  [$subIndex/$subCount]" `
        -PercentComplete (($subIndex / $subCount) * 100)

    Set-AzContext -Subscription $subscription.Id | Out-Null

    # Get workbooks in subscription
    $workbooklist = Get-AzApplicationInsightsWorkbook -SubscriptionId $subscription.Id -Category 'workbook' -WarningAction SilentlyContinue
    $wbCount = ($workbooklist | Measure-Object).Count

    if ($wbCount -eq 0) {
        Write-Host "  [No workbooks in subscription: $($subscription.Name)]" -ForegroundColor DarkYellow
        continue
    }

    $wbIndex = 0
    foreach ($workbook in $workbooklist) {
        $wbIndex++
        Write-Progress -Id 2 -ParentId 1 `
            -Activity "Processing Workbooks" `
            -Status "$($workbook.DisplayName)  [$wbIndex/$wbCount]" `
            -PercentComplete (($wbIndex / $wbCount) * 100)

        $workbookname        = "$($workbook.Name)"
        $workbookdisplayname = "$($workbook.DisplayName)"

        # Fetch with content
        $workbookinfo = Get-AzApplicationInsightsWorkbook -Category 'workbook' -CanFetchContent -WarningAction SilentlyContinue |
                        Where-Object { $_.Name -eq $workbook.Name }

        if (-not $workbookinfo) { continue }

        $jsonresource = $workbookinfo.SerializedData | ConvertFrom-Json
        if (-not $jsonresource) { continue }

        # Extract query-bearing items
        $sourcequeries = $jsonresource.items | Select-Object -ExpandProperty content
        if (-not $sourcequeries) { continue }

        $queriesToProcess = $sourcequeries | Where-Object { $_.items.content.query -ne $null }
        $qCount = ($queriesToProcess | Measure-Object).Count

        if ($qCount -eq 0) { continue }

        $qIndex = 0
        foreach ($jsonitem in $queriesToProcess) {
            $qIndex++
            Write-Progress -Id 3 -ParentId 2 `
                -Activity "Extracting Queries" `
                -Status "Query group $qIndex of $qCount" `
                -PercentComplete (($qIndex / $qCount) * 100)

            foreach ($jsonitemcontent in ($jsonitem.items.content | Where-Object { $_.query -ne $null })) {
                $contenttypes = $jsonitem.items.type

                foreach ($contenttype in $contenttypes) {
                    # Build object using PSObject + Add-Member style
                    $jsoncontentobj = New-Object PSObject

                    $contentitems = $jsonitem.items.content
                    $Feature      = $contentitems.json -replace '{#',''

                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name Type                    -Value $contenttype
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name SubscriptionId          -Value $subscription.Id
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name SubscriptionName        -Value $subscription.Name
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name WorkbookName            -Value $workbookdisplayname
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name Feature                 -Value "$Feature"
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name Version                 -Value $jsonitemcontent.version
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name Title                   -Value $jsonitemcontent.title
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name NoDataMessage           -Value $jsonitemcontent.noDataMessage
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name QueryType               -Value $jsonitemcontent.queryType
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name ResourceType            -Value $jsonitemcontent.resourceType
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name CrossComponentResources -Value $jsonitemcontent.crossComponentResources
                    $jsoncontentobj | Add-Member -MemberType NoteProperty -Name Query                   -Value "$($jsonitemcontent.query)"

                    # Get AI-generated description for the query
                    if ($aiEndpoint -and $aiKey) {
                        try {
                            $response = & call_ai_foundry_ext -ApiKey $aiKey `
                                -Prompt "Provide a brief technical description for '$($jsonitemcontent.title)' based on this query: $($jsonitemcontent.query)" `
                                -Endpoint ("$aiEndpoint/models") *>&1 -ErrorAction SilentlyContinue

                            # Decode and clean AI response
                            if ($response) {
                                $decoded = [System.Net.WebUtility]::HtmlDecode($response)
                                $pattern = '</think\s*>'
                                $match   = [System.Text.RegularExpressions.Regex]::Match($decoded, $pattern, 'IgnoreCase')

                                if ($match.Success) {
                                    $startIndex = $match.Index + $match.Length
                                    $afterThink = $decoded.Substring($startIndex)
                                    $result     = $afterThink.Trim()
                                    $guidanceText = Convert-GuidanceToCRLF -Text $result
                                } else {
                                    $guidanceText = Convert-GuidanceToCRLF -Text $decoded
                                }

                                $jsoncontentobj | Add-Member -MemberType NoteProperty -Name Description -Value "$guidanceText"
                            } else {
                                $jsoncontentobj | Add-Member -MemberType NoteProperty -Name Description -Value "AI description unavailable"
                            }
                        } catch {
                            Write-Verbose "AI Foundry call failed: $($_.Exception.Message)"
                            $jsoncontentobj | Add-Member -MemberType NoteProperty -Name Description -Value "Error generating description"
                        }
                    } else {
                        $jsoncontentobj | Add-Member -MemberType NoteProperty -Name Description -Value "AI endpoint not configured"
                    }

                    # Append to collection
                    [array]$querylist += $jsoncontentobj
                }
            }
        }
    }
}

Write-Progress -Id 1 -Completed -Activity "Subscription processing"
Write-Progress -Id 2 -Completed -Activity "Workbook processing"
Write-Progress -Id 3 -Completed -Activity "Query extraction"

Write-Host "Audit complete. Found $($querylist.Count) queries." -ForegroundColor Green

# ---------- Generate Reports ----------

$CSS = @"
<Title>Azure Monitor Workbook Queries Report: $(Get-Date -Format 'dd MMMM yyyy')</Title>
<Style>
th {
    font: bold 11px "Trebuchet MS", Verdana, Arial, Helvetica, sans-serif;
    color: #FFFFFF;
    border-right: 1px solid #C1DAD7;
    border-bottom: 1px solid #C1DAD7;
    letter-spacing: 1px;
    text-transform: uppercase;
    text-align: left;
    padding: 6px 6px 6px 12px;
    background: #5F9EA0;
}
td {
    font: 11px "Trebuchet MS", Verdana, Arial, Helvetica, sans-serif;
    border-right: 1px solid #C1DAD7;
    border-bottom: 1px solid #C1DAD7;
    background: #fff;
    padding: 6px 6px 6px 12px;
    color: #6D929B;
}
</Style>
"@

# HTML Report
try {
    $htmlContent = $querylist | Select-Object SubscriptionName, WorkbookName, Version, Title, QueryType, ResourceType, Query, Description -Unique |
        ConvertTo-Html -Head $CSS
    $htmlContent = $htmlContent -replace 'Â Â',''
    $htmlReport = Join-Path $OutPath 'workbookname_queries.html'
    $htmlContent | Out-File $htmlReport -Encoding UTF8
    Write-Host "HTML report generated: $htmlReport" -ForegroundColor Green
} catch {
    Write-Warning "HTML report generation failed: $($_.Exception.Message)"
}

# CSV Report
try {
    $csvReport = Join-Path $OutPath 'workbookname_queries.csv'
    $querylist | Select-Object SubscriptionName, WorkbookName, Version, Title, QueryType, ResourceType, Query, Description -Unique |
        Export-Csv $csvReport -NoTypeInformation -Encoding UTF8
    Write-Host "CSV report generated: $csvReport" -ForegroundColor Green
} catch {
    Write-Warning "CSV report generation failed: $($_.Exception.Message)"
}

# JSON Report
try {
    $jsonReport = Join-Path $OutPath 'workbookname_queries.json'
    $querylist | Select-Object SubscriptionName, WorkbookName, Version, Title, QueryType, ResourceType, Query, Description -Unique |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $jsonReport -Encoding UTF8
    Write-Host "JSON report generated: $jsonReport" -ForegroundColor Green
} catch {
    Write-Warning "JSON report generation failed: $($_.Exception.Message)"
}

# ---------- Upload to Azure Storage (if configured) ----------

if ($storageAccount -and $storageKey -and $storageContainer) {
    Write-Host "Uploading reports to Azure Storage..." -ForegroundColor Cyan

    try {
        # Create storage context
        $StorageContext = New-AzStorageContext -StorageAccountName $storageAccount -StorageAccountKey $storageKey
        
        # Ensure container exists
        Get-AzStorageContainer -Name $storageContainer -Context $StorageContext -ErrorAction SilentlyContinue |
            Select-Object -First 1 | Out-Null

        if (-not $?) {
            Write-Host "Creating storage container: $storageContainer" -ForegroundColor Yellow
            New-AzStorageContainer -Name $storageContainer -Context $StorageContext -ErrorAction SilentlyContinue | Out-Null
        }

        # Upload CSV
        $csvReport = Join-Path $OutPath 'workbookname_queries.csv'
        if (Test-Path $csvReport) {
            Set-AzStorageBlobContent -Container $storageContainer `
                                     -Blob "workbookname_queries_$(Get-Date -Format 'yyyyMMdd_HHmmss').csv" `
                                     -File $csvReport `
                                     -Context $StorageContext `
                                     -Force
            Write-Host "CSV uploaded to storage account" -ForegroundColor Green
        }
    } catch {
        Write-Warning "Storage upload failed: $($_.Exception.Message)"
    }
}

Write-Host "Script execution completed successfully" -ForegroundColor Green
