[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath
)

$ErrorActionPreference = 'Stop'

function Resolve-Adb {
    if ($env:ANDROID_HOME) {
        $candidate = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
        if (Test-Path $candidate) { return $candidate }
    }

    if ($env:ANDROID_SDK_ROOT) {
        $candidate = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools/adb.exe'
        if (Test-Path $candidate) { return $candidate }
    }

    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $wingetRoot = Join-Path $env:LOCALAPPDATA 'Microsoft/WinGet/Packages'
    $candidate = Get-ChildItem -Path $wingetRoot -Filter adb.exe -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($candidate) { return $candidate }

    throw 'adb.exe was not found. Set ANDROID_HOME or install Android Platform Tools.'
}

if (-not (Test-Path $ApkPath)) {
    throw "APK was not found: $ApkPath"
}

$adb = Resolve-Adb
$deviceLines = & $adb devices
if ($LASTEXITCODE -ne 0) {
    throw 'adb devices failed.'
}

$devices = @($deviceLines | Where-Object { $_ -match '^([^\s]+)\s+device$' } | ForEach-Object {
    [regex]::Match($_, '^([^\s]+)\s+device$').Groups[1].Value
})

if ($devices.Count -eq 0) {
    throw 'No authorized Android device is attached. Connect a device, enable USB debugging, and accept the RSA prompt.'
}

foreach ($device in $devices) {
    Write-Host "Installing Phone Sync USB-C on $device..."
    & $adb -s $device install -r $ApkPath
    if ($LASTEXITCODE -ne 0) {
        throw "APK installation failed on device $device."
    }
}

Write-Host "Installed $ApkPath on $($devices.Count) authorized device(s). Existing app data was preserved."