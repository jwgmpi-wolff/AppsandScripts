[CmdletBinding()]
param(
    [int] $Port = 3000,
    [switch] $NoBrowser
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw 'Node.js 20 or newer is required.'
}

$webRoot = Join-Path $PSScriptRoot 'web'
Push-Location $webRoot
try {
    npm install
    if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }

    npm test
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed; the server was not started.' }

    $env:PORT = $Port
    if (-not $NoBrowser) {
        Start-Process "http://localhost:$Port"
    }
    npm start
}
finally {
    Pop-Location
}