param(
    [Parameter(Mandatory = $false)]
    [int]$Port = 8000,

    [Parameter(Mandatory = $false)]
    [switch]$SkipAzureCheck
)

$ErrorActionPreference = "Stop"

Write-Host "Starting local deployment for FinOps Savings Dashboard..."

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "Python was not found on PATH. Install Python 3.11+ and try again."
}

if (-not (Test-Path "venv")) {
    Write-Host "Creating virtual environment..."
    python -m venv venv
}

Write-Host "Installing dependencies..."
& .\venv\Scripts\python -m pip install --upgrade pip
& .\venv\Scripts\python -m pip install -r requirements.txt

if (-not (Test-Path ".env") -and (Test-Path ".env.example")) {
    Write-Host "Creating .env from .env.example..."
    Copy-Item .env.example .env
}

if (-not $SkipAzureCheck) {
    Write-Host "Checking Azure CLI login status..."
    az account show 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Azure CLI is not logged in. Run 'az login' before using live cost data." -ForegroundColor Yellow
    }
}

Write-Host "Launching dashboard on http://127.0.0.1:$Port"
& .\venv\Scripts\python -m uvicorn dashboard_api:app --host 127.0.0.1 --port $Port --log-level info
