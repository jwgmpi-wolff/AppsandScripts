# PowerShell Script: Transfer Files from OneDrive to Azure Storage Account
# Using your login context - NO App Registration needed
# Run this script in PowerShell ISE

# ========================================
# MINIMAL CONFIGURATION
# ========================================

# OneDrive Configuration
$userEmail = $null  # Will be auto-detected from your login
$oneDriveFolderPath = "/root"  # Modify as needed (e.g., "/root/Documents")

# Logging
$logFile = "C:\Logs\OneDriveTransfer_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"
$null = New-Item -ItemType Directory -Path "C:\Logs" -Force -ErrorAction SilentlyContinue

# ========================================
# LOGGING FUNCTION
# ========================================

function Write-Log {
    param(
        [string]$Message,
        [ValidateSet("INFO", "WARNING", "ERROR", "SUCCESS")]
        [string]$Level = "INFO"
    )
    
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $logMessage = "[$timestamp][$Level] $Message"
    
    Write-Host $logMessage -ForegroundColor $(
        switch ($Level) {
            "ERROR" { "Red" }
            "WARNING" { "Yellow" }
            "SUCCESS" { "Green" }
            default { "White" }
        }
    )
    
    Add-Content -Path $logFile -Value $logMessage
}

# ========================================
# AUTHENTICATION SECTION
# ========================================

function Connect-ToMicrosoftGraph {
    <#
    .SYNOPSIS
    Connects to Microsoft Graph using device authentication flow
    Non-interactive - user visits devicelogin.microsoft.com with provided code
    #>
    
    try {
        Write-Log "Authenticating to Microsoft Graph (Device Flow)..." "INFO"
        
        # Try to use cached credentials first
        $context = Get-MgContext -ErrorAction SilentlyContinue
        if ($context) {
            Write-Log "Using cached Microsoft Graph credentials" "SUCCESS"
            return
        }
        
        # Use device authentication flow - user gets a code to enter at devicelogin.microsoft.com
        Write-Host "`n" -ForegroundColor Cyan
        Write-Host "=== Microsoft Graph Device Authentication ===" -ForegroundColor Cyan
        Write-Host "Follow these steps:" -ForegroundColor White
        Write-Host "1. Visit: https://microsoft.com/devicelogin" -ForegroundColor Yellow
        Write-Host "2. Enter the device code shown below" -ForegroundColor Yellow
        Write-Host "3. Sign in with your Microsoft account" -ForegroundColor Yellow
        Write-Host "============================================`n" -ForegroundColor Cyan
        
        # Disable WAM for console output
        $ErrorActionPreference = "Continue"
        Connect-MgGraph -Scopes @(
            "Files.Read.All",
            "User.Read"
        ) -UseDeviceAuthentication -NoWelcome -ContextScope CurrentUser -ClientId "14d82eec-204b-4c2f-b098-0da8e7120ff7" 2>&1 | ForEach-Object { 
            if ($_ -match "code|device|To sign in") { 
                Write-Host $_ -ForegroundColor Cyan 
            }
        }
        $ErrorActionPreference = "Stop"
        
        # Verify connection successful
        Start-Sleep -Seconds 2
        $context = Get-MgContext -ErrorAction Stop
        if ($context) {
            Write-Log "Successfully authenticated to Microsoft Graph" "SUCCESS"
        } else {
            throw "Failed to establish context after authentication"
        }
    }
    catch {
        Write-Log "Failed to authenticate to Microsoft Graph: $_" "ERROR"
        throw
    }
}

function Connect-ToAzure {
    <#
    .SYNOPSIS
    Connects to Azure using device authentication flow
    Non-interactive - user visits microsoft.com/devicelogin with provided code
    #>
    
    try {
        Write-Log "Authenticating to Azure (Device Flow)..." "INFO"
        
        # Check if already authenticated
        $currentAccount = Get-AzContext -ErrorAction SilentlyContinue
        if ($currentAccount) {
            Write-Log "Using existing Azure session: $($currentAccount.Account.Id)" "SUCCESS"
            return
        }
        
        # Use device authentication flow - non-interactive
        Write-Host "`n" -ForegroundColor Cyan
        Write-Host "=== Azure Device Authentication ===" -ForegroundColor Cyan
        Write-Host "You will be prompted with a device code." -ForegroundColor White
        Write-Host "Visit: https://microsoft.com/devicelogin" -ForegroundColor Yellow
        Write-Host "Enter the code shown below to authenticate" -ForegroundColor White
        Write-Host "======================================`n" -ForegroundColor Cyan
        
        $ErrorActionPreference = "Continue"
        Connect-AzAccount -UseDeviceAuthentication 2>&1 | Out-Null
        $ErrorActionPreference = "Stop"
        
        # Verify connection successful
        $currentAccount = Get-AzContext -ErrorAction Stop
        if ($currentAccount) {
            Write-Log "Successfully authenticated to Azure" "SUCCESS"
        }
    }
    catch {
        Write-Log "Failed to authenticate to Azure: $_" "ERROR"
        throw
    }
}

function Get-UserEmail {
    <#
    .SYNOPSIS
    Gets the current user's email from Microsoft Graph
    #>
    
    try {
        Write-Log "Retrieving your email address..." "INFO"
        
        $myProfile = Get-MgUser -UserId "me" -ErrorAction Stop
        $email = $myProfile.Mail
        
        if ([string]::IsNullOrWhiteSpace($email)) {
            $email = $myProfile.UserPrincipalName
        }
        
        Write-Log "Using account: $email" "SUCCESS"
        return $email
    }
    catch {
        Write-Log "Failed to retrieve email: $_" "ERROR"
        throw
    }
}

# ========================================
# MICROSOFT GRAPH API FUNCTIONS
# ========================================

function Get-OneDriveFileList {
    <#
    .SYNOPSIS
    Retrieves list of files from OneDrive using Microsoft Graph API
    #>
    
    param(
        [string]$UserEmail,
        [string]$FolderPath
    )
    
    try {
        Write-Log "Retrieving file list from OneDrive folder: $FolderPath" "INFO"
        
        # Get user
        $user = Get-MgUser -UserId $UserEmail
        $userId = $user.Id
        
        # Get drive items
        try {
            $driveItems = Get-MgUserDriveItemChildrenByPath -UserId $userId -Path $FolderPath -ErrorAction Stop
        }
        catch {
            Write-Log "Folder not found. Using root folder instead." "WARNING"
            $driveItems = Get-MgUserDriveRoot -UserId $userId | Get-MgDirectoryObjectChildrenAsListItem
        }
        
        $files = @()
        foreach ($item in $driveItems) {
            if ($item.File) {
                $files += [PSCustomObject]@{
                    Name     = $item.Name
                    Id       = $item.Id
                    Size     = $item.Size
                    WebUrl   = $item.WebUrl
                }
            }
        }
        
        Write-Log "Found $($files.Count) files in OneDrive" "SUCCESS"
        return $files, $userId
    }
    catch {
        Write-Log "Failed to retrieve OneDrive file list: $_" "ERROR"
        throw
    }
}

function Download-OneDriveFile {
    <#
    .SYNOPSIS
    Downloads a file from OneDrive
    #>
    
    param(
        [string]$UserId,
        [string]$FileId,
        [string]$FileName,
        [string]$DestinationPath
    )
    
    try {
        Write-Log "Downloading file: $FileName" "INFO"
        
        $fileFullPath = Join-Path -Path $DestinationPath -ChildPath $FileName
        
        Get-MgUserDriveItemContent -UserId $UserId -DriveItemId $FileId -OutFile $fileFullPath
        
        Write-Log "Successfully downloaded: $FileName" "SUCCESS"
        return $fileFullPath
    }
    catch {
        Write-Log "Failed to download file $FileName`: $_" "ERROR"
        throw
    }
}

# ========================================
# AZURE STORAGE FUNCTIONS
# ========================================

function Select-StorageAccount {
    <#
    .SYNOPSIS
    Lists your storage accounts and lets you choose one
    Auto-selects if only one available
    #>
    
    try {
        Write-Log "Retrieving your Azure Storage Accounts..." "INFO"
        
        $storageAccounts = Get-AzStorageAccount
        
        if ($storageAccounts.Count -eq 0) {
            Write-Log "No storage accounts found in your subscription" "ERROR"
            throw "No storage accounts available"
        }
        
        # Auto-select first account if only one available
        if ($storageAccounts.Count -eq 1) {
            Write-Log "Only one storage account found, auto-selecting: $($storageAccounts[0].StorageAccountName)" "INFO"
            Write-Host "Selected Storage Account: $($storageAccounts[0].StorageAccountName)" -ForegroundColor Green
            return $storageAccounts[0]
        }
        
        Write-Host "`nAvailable Storage Accounts:" -ForegroundColor Cyan
        for ($i = 0; $i -lt $storageAccounts.Count; $i++) {
            Write-Host "$($i + 1). $($storageAccounts[$i].StorageAccountName) (Resource Group: $($storageAccounts[$i].ResourceGroupName))"
        }
        
        $selection = Read-Host "Select a storage account number (1-$($storageAccounts.Count))"
        $selectedAccount = $storageAccounts[[int]$selection - 1]
        
        Write-Log "Selected storage account: $($selectedAccount.StorageAccountName)" "SUCCESS"
        return $selectedAccount
    }
    catch {
        Write-Log "Failed to select storage account: $_" "ERROR"
        throw
    }
}

function Select-Container {
    <#
    .SYNOPSIS
    Lists containers in a storage account and lets you choose one
    Auto-selects if only one available, creates default if none exist
    #>
    
    param(
        [string]$StorageAccountName,
        [string]$ResourceGroupName
    )
    
    try {
        Write-Log "Retrieving containers from $StorageAccountName..." "INFO"
        
        $containers = Get-AzStorageContainer -ResourceGroupName $ResourceGroupName -StorageAccountName $StorageAccountName -ErrorAction SilentlyContinue
        
        if ($containers.Count -eq 0) {
            Write-Host "`nNo containers found. Creating default container 'onedrive-files'..." -ForegroundColor Yellow
            $containerName = "onedrive-files"
            New-AzStorageContainer -ResourceGroupName $ResourceGroupName -StorageAccountName $StorageAccountName -Name $containerName | Out-Null
            Write-Log "Created container: $containerName" "SUCCESS"
            return $containerName
        }
        
        # Auto-select if only one container
        if ($containers.Count -eq 1) {
            Write-Log "Only one container found, auto-selecting: $($containers[0].Name)" "INFO"
            Write-Host "Selected container: $($containers[0].Name)" -ForegroundColor Green
            return $containers[0].Name
        }
        
        Write-Host "`nAvailable Containers:" -ForegroundColor Cyan
        for ($i = 0; $i -lt $containers.Count; $i++) {
            Write-Host "$($i + 1). $($containers[$i].Name)"
        }
        
        $selection = Read-Host "Select a container number (1-$($containers.Count))"
        $selectedContainer = $containers[[int]$selection - 1].Name
        Write-Log "Selected container: $selectedContainer" "SUCCESS"
        return $selectedContainer
    }
    catch {
        Write-Log "Failed to select container: $_" "ERROR"
        throw
    }
}

function Upload-FileToAzureBlob {
    <#
    .SYNOPSIS
    Uploads a file to Azure Blob Storage
    #>
    
    param(
        [string]$StorageAccountName,
        [string]$ResourceGroupName,
        [string]$ContainerName,
        [string]$FilePath,
        [string]$BlobName
    )
    
    try {
        Write-Log "Uploading file to Azure Blob Storage: $BlobName" "INFO"
        
        $file = Get-Item -Path $FilePath
        
        $storageAccount = Get-AzStorageAccount -ResourceGroupName $ResourceGroupName -StorageAccountName $StorageAccountName
        
        Set-AzStorageBlobContent -File $FilePath `
            -Container $ContainerName `
            -Blob $BlobName `
            -Context $storageAccount.Context `
            -Force | Out-Null
        
        Write-Log "Successfully uploaded blob: $BlobName (Size: $(($file.Length / 1MB).ToString('F2')) MB)" "SUCCESS"
    }
    catch {
        Write-Log "Failed to upload file to Azure Blob Storage: $_" "ERROR"
        throw
    }
}

# ========================================
# MAIN TRANSFER PROCESS
# ========================================

function Start-OneDriveToAzureTransfer {
    
    try {
        Write-Log "========== OneDrive to Azure Storage Transfer Started ==========" "INFO"
        
        # Create temporary download directory
        $tempDir = "C:\Temp\OneDrive_Transfer_$(Get-Date -Format 'yyyyMMdd_HHmmss')"
        $null = New-Item -ItemType Directory -Path $tempDir -Force
        Write-Log "Created temporary directory: $tempDir" "INFO"
        
        # Step 1: Authenticate to Microsoft Graph
        Connect-ToMicrosoftGraph
        
        # Step 2: Get user email
        $userEmail = Get-UserEmail
        
        # Step 3: Get file list from OneDrive
        $files, $userId = Get-OneDriveFileList -UserEmail $userEmail -FolderPath $oneDriveFolderPath
        
        if ($files.Count -eq 0) {
            Write-Log "No files found in OneDrive folder. Exiting." "WARNING"
            return
        }
        
        # Step 4: Authenticate to Azure
        Connect-ToAzure
        
        # Step 5: Select storage account and container
        $selectedAccount = Select-StorageAccount
        $selectedContainer = Select-Container -StorageAccountName $selectedAccount.StorageAccountName -ResourceGroupName $selectedAccount.ResourceGroupName
        
        # Step 6: Process each file
        $successCount = 0
        $failureCount = 0
        
        Write-Host "`nStarting file transfers..." -ForegroundColor Cyan
        
        foreach ($file in $files) {
            try {
                # Download from OneDrive
                $downloadedPath = Download-OneDriveFile -UserId $userId -FileId $file.Id -FileName $file.Name -DestinationPath $tempDir
                
                # Upload to Azure Blob Storage
                Upload-FileToAzureBlob -StorageAccountName $selectedAccount.StorageAccountName `
                    -ResourceGroupName $selectedAccount.ResourceGroupName `
                    -ContainerName $selectedContainer `
                    -FilePath $downloadedPath `
                    -BlobName $file.Name
                
                $successCount++
            }
            catch {
                $failureCount++
                Write-Log "Skipping file: $($file.Name)" "WARNING"
            }
        }
        
        # Cleanup temporary directory
        Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
        Write-Log "Cleaned up temporary directory" "INFO"
        
        # Summary
        Write-Log "========== Transfer Summary ==========" "INFO"
        Write-Log "Total files processed: $($files.Count)" "INFO"
        Write-Log "Successful transfers: $successCount" "SUCCESS"
        Write-Log "Failed transfers: $failureCount" $(if ($failureCount -gt 0) { "WARNING" } else { "SUCCESS" })
        Write-Log "========== Transfer Completed ==========" "INFO"
        
        Write-Host "`nLog file: $logFile" -ForegroundColor Yellow
    }
    catch {
        Write-Log "Transfer process failed: $_" "ERROR"
        exit 1
    }
}

# ========================================
# EXECUTION
# ========================================

# Disable WAM for terminal environments - use console device flow instead
$env:AZURE_AUTH_METHOD = "CLI"
if (-not (Test-Path -Path "$env:APPDATA\Microsoft\Graph")) {
    $null = New-Item -ItemType Directory -Path "$env:APPDATA\Microsoft\Graph" -Force
}

# Check and install required modules
$requiredModules = @("Microsoft.Graph.Authentication", "Microsoft.Graph.Files", "Microsoft.Graph.Users", "Az.Accounts", "Az.Storage")

foreach ($module in $requiredModules) {
    if (-not (Get-Module -ListAvailable -Name $module)) {
        Write-Host "Installing required module: $module" -ForegroundColor Yellow
        Install-Module -Name $module -Force -AllowClobber -Scope CurrentUser
    }
}

# Import required modules
Import-Module Microsoft.Graph.Authentication -ErrorAction Stop
Import-Module Microsoft.Graph.Files -ErrorAction Stop
Import-Module Microsoft.Graph.Users -ErrorAction Stop
Import-Module Az.Accounts -ErrorAction Stop
Import-Module Az.Storage -ErrorAction Stop

# Run the transfer
Start-OneDriveToAzureTransfer
