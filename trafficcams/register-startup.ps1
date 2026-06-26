# Register Traffic Cams as Windows Startup Service
# Run this as Administrator to enable automatic startup

param(
    [switch]$Remove = $false,
    [switch]$Enable = $false,
    [switch]$Disable = $false,
    [switch]$Status = $false
)

# Check admin rights
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")

if (-not $isAdmin) {
    Write-Host "⚠️  This script must be run as Administrator" -ForegroundColor Red
    Write-Host "`nRestarting as Administrator..." -ForegroundColor Yellow
    Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Status" -Verb RunAs
    exit
}

$taskName = "Traffic-Cams-Server"
$taskPath = "\Microsoft\Windows\Custom\"
$fullTaskName = "$taskPath$taskName"
$scriptDir = Split-Path -Parent $PSCommandPath
$batchFile = Join-Path $scriptDir "start-service.bat"
$logDir = Join-Path $scriptDir "logs"

Write-Host "`n" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Traffic Cams Startup Service Manager" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Create logs directory if it doesn't exist
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    Write-Host "✅ Created logs directory" -ForegroundColor Green
}

if ($Remove) {
    Write-Host "Removing startup task..." -ForegroundColor Yellow
    try {
        Unregister-ScheduledTask -TaskPath $taskPath -TaskName $taskName -Confirm:$false -ErrorAction Stop
        Write-Host "✅ Task removed successfully" -ForegroundColor Green
    } catch {
        Write-Host "ℹ️  Task does not exist or already removed" -ForegroundColor Blue
    }
    exit
}

if ($Disable) {
    Write-Host "Disabling startup task..." -ForegroundColor Yellow
    try {
        Disable-ScheduledTask -TaskPath $taskPath -TaskName $taskName -ErrorAction Stop
        Write-Host "✅ Task disabled - server will not start on boot" -ForegroundColor Green
    } catch {
        Write-Host "❌ Could not disable task: $_" -ForegroundColor Red
    }
    exit
}

if ($Status) {
    Write-Host "Checking task status..." -ForegroundColor Cyan
    try {
        $task = Get-ScheduledTask -TaskPath $taskPath -TaskName $taskName -ErrorAction Stop
        $state = $task.State
        
        if ($state -eq "Ready") {
            Write-Host "✅ Task is ENABLED and ready to run on startup" -ForegroundColor Green
        } else {
            Write-Host "⚠️  Task is $state" -ForegroundColor Yellow
        }
        
        Write-Host ""
        Write-Host "Task Details:" -ForegroundColor Cyan
        Write-Host "  Name: $taskName"
        Write-Host "  Path: $fullTaskName"
        Write-Host "  State: $state"
        Write-Host "  Trigger: At system startup"
        Write-Host "  Run: Hidden background process"
        
        if (Test-Path (Join-Path $logDir "startup.log")) {
            Write-Host ""
            Write-Host "Recent Startup Log:" -ForegroundColor Cyan
            Get-Content (Join-Path $logDir "startup.log") -Tail 5
        }
    } catch {
        Write-Host "❌ Task not found. Please run with -Enable flag to set up startup." -ForegroundColor Red
    }
    exit
}

if ($Enable) {
    Write-Host "Setting up Traffic Cams Server for automatic startup..." -ForegroundColor Green
    Write-Host ""
    
    # Check if batch file exists
    if (-not (Test-Path $batchFile)) {
        Write-Host "❌ Error: start-service.bat not found at $batchFile" -ForegroundColor Red
        exit 1
    }
    
    Write-Host "✅ Batch file found" -ForegroundColor Green
    
    # Create task action
    $action = New-ScheduledTaskAction -Execute $batchFile
    
    # Create trigger (at system startup)
    $trigger = New-ScheduledTaskTrigger -AtStartup
    
    # Create settings
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RunOnlyIfNetworkAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 5)
    
    # Create principal (run with highest privileges)
    $principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
    
    # Create description
    $description = "Starts the Puget Sound Traffic Cameras server in the background"
    
    # Register the task
    try {
        # Remove existing task if it exists
        try {
            Unregister-ScheduledTask -TaskPath $taskPath -TaskName $taskName -Confirm:$false -ErrorAction Stop
            Write-Host "ℹ️  Removed previous task configuration" -ForegroundColor Blue
        } catch {}
        
        # Create folder if it doesn't exist
        $parentPath = "\" + ($taskPath.Trim("\") -split "\\" | Select-Object -SkipLast 1) -join "\"
        $folderName = $taskPath.Trim("\") -split "\\" | Select-Object -Last 1
        
        try {
            New-Item -Path $parentPath -Name $folderName -Force -ErrorAction Stop | Out-Null
        } catch {}
        
        Register-ScheduledTask -TaskPath $taskPath -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description $description -Force | Out-Null
        
        Write-Host "✅ Startup task created successfully!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Configuration:" -ForegroundColor Cyan
        Write-Host "  📍 Task Name: $taskName"
        Write-Host "  📁 Task Path: $fullTaskName"
        Write-Host "  ⏰ Trigger: At system startup"
        Write-Host "  🔒 Privilege: System (Highest)"
        Write-Host "  🔄 Auto-restart: On failure (3 retries, 5 min intervals)"
        Write-Host ""
        Write-Host "✅ The Traffic Cams Server will now start automatically on system boot!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Access your server at:" -ForegroundColor Cyan
        Write-Host "  🌐 http://trafficcams.local" -ForegroundColor Yellow
        Write-Host "  📍 http://localhost" -ForegroundColor Yellow
        
    } catch {
        Write-Host "❌ Error creating task: $_" -ForegroundColor Red
        exit 1
    }
    
    exit 0
}

# If no parameters, show help and status
Write-Host "Usage:" -ForegroundColor Cyan
Write-Host "  .\register-startup.ps1 -Enable     Setup automatic startup" -ForegroundColor Green
Write-Host "  .\register-startup.ps1 -Status     Check current status" -ForegroundColor Blue
Write-Host "  .\register-startup.ps1 -Disable    Disable automatic startup" -ForegroundColor Yellow
Write-Host "  .\register-startup.ps1 -Remove     Remove startup task" -ForegroundColor Red
Write-Host ""

# Show current status
Write-Host "Current Status:" -ForegroundColor Cyan
try {
    $task = Get-ScheduledTask -TaskPath $taskPath -TaskName $taskName -ErrorAction Stop
    $state = $task.State
    Write-Host "✅ Task exists (State: $state)" -ForegroundColor Green
} catch {
    Write-Host "❌ Task not found (not configured for startup)" -ForegroundColor Red
}

Write-Host ""
Write-Host "Example: Run the following command to enable automatic startup:" -ForegroundColor Yellow
Write-Host "  .\register-startup.ps1 -Enable" -ForegroundColor White
Write-Host ""
