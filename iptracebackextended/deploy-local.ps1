param(
  [int]$Port = 3001,
  [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

Write-Host "[local] Starting local deployment for IPTraceback Extended..." -ForegroundColor Cyan

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
  throw "Node.js is not installed. Install Node.js 18+ and retry."
}

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
  throw "npm is not installed. Install npm and retry."
}

if (-not $SkipInstall) {
  Write-Host "[local] Installing dependencies..." -ForegroundColor Yellow
  npm ci
}

$env:PORT = $Port
Write-Host "[local] Launching app on http://localhost:$Port" -ForegroundColor Green
npm run start
