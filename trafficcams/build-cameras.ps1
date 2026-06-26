# Read all extracted URLs
$urls = Get-Content urls.txt

# Better categorization - check most specific patterns first
$airport = @($urls | Where-Object { $_ -like '*airport*' })
$everett = @($urls | Where-Object { $_ -like '*everett*' })
$snohomish = @($urls | Where-Object { $_ -like '*snoco*' })
$wsdot = @($urls | Where-Object { ($_ -like '*wsdot*') -and ($_ -notlike '*airport*') })

Write-Host "✓ URL Categories:"
Write-Host "  WSDOT: $($wsdot.Count)"
Write-Host "  Airport: $($airport.Count)"
Write-Host "  Everett: $($everett.Count)"
Write-Host "  Snohomish: $($snohomish.Count)"
Write-Host "  Total: $(($wsdot.Count + $airport.Count + $everett.Count + $snohomish.Count))"

# Build array
$json = @()

# WSDOT (561 cameras)
$i = 1
foreach ($url in $wsdot) {
    if ($url -match '/nw/([^.]+)\.jpg') {
        $code = $matches[1].ToUpper()
        $route = [regex]::Match($code, '^([A-Z0-9]+)').Value
        $location = "WSDOT $route"
    } else {
        $location = "WSDOT Highway"
    }
    $json += @{
        id = $i
        name = "WSDOT Camera $i"
        location = $location
        source = "wsdot"
        url = $url
    }
    $i++
}

# Airport (10 cameras)
foreach ($url in $airport) {
    if ($url -match '/airports/([^.]+)\.jpg') {
        $code = $matches[1]
        if ($code -like 'arl*') { $location = "Arlington Airport" }
        elseif ($code -like 'auburn*') { $location = "Auburn Airport" }
        elseif ($code -like 'renton*') { $location = "Renton Airport" }
        else { $location = "Regional Airport" }
    } else {
        $location = "Regional Airport"
    }
    $json += @{
        id = $i
        name = "Airport Camera"
        location = $location
        source = "airport"
        url = $url
    }
    $i++
}

# Everett (34 cameras)
foreach ($url in $everett) {
    $json += @{
        id = $i
        name = "Everett Camera"
        location = "City of Everett"
        source = "everett"
        url = $url
    }
    $i++
}

# Snohomish (1 camera if any)
foreach ($url in $snohomish) {
    $json += @{
        id = $i
        name = "Snohomish Traffic Camera"
        location = "Snohomish County"
        source = "snohomish"
        url = $url
    }
    $i++
}

# Convert to JavaScript
$js = "        const cameras = [`n"
foreach ($cam in $json) {
    $js += "            {id:$($cam.id),name:'$($cam.name)',location:'$($cam.location)',source:'$($cam.source)',url:'$($cam.url)'},`n"
}
$js = $js.TrimEnd(",`n") + "`n        ];"

$js | Out-File camera-array.js -Encoding UTF8
Write-Host "✓ Generated camera-array.js with $($json.Count) total cameras"
