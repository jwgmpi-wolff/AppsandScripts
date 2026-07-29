param(
    [int]$Port = 5000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$project = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $project

if (-not (Test-Path '.venv')) {
    Write-Host '[local] Creating virtual environment...' -ForegroundColor Cyan
    python -m venv .venv
}

Write-Host '[local] Installing dependencies...' -ForegroundColor Cyan
.\.venv\Scripts\python.exe -m pip install -r requirements.txt

$env:PORT = "$Port"
if (-not $env:APP_AUTO_PORT) {
    $env:APP_AUTO_PORT = '1'
}
Write-Host "[local] Starting app (preferred port: http://localhost:$Port)." -ForegroundColor Green
Write-Host "[local] If busy, the app will auto-select the next available port." -ForegroundColor Yellow
.\.venv\Scripts\python.exe app.py
