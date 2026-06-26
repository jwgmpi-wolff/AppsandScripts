# Read all extracted URLs
$urls = Get-Content urls.txt

# Categorize URLs
$wsdot = @($urls | Where-Object { $_ -like '*wsdot*' })
$everett = @($urls | Where-Object { $_ -like '*everett*' })
$snohomish = @($urls | Where-Object { $_ -like '*snoco*' })
$airport = @($urls | Where-Object { $_ -like '*airport*' })

Write-Host "WSDOT: $($wsdot.Count)"
Write-Host "Everett: $($everett.Count)"
Write-Host "Snohomish: $($snohomish.Count)"
Write-Host "Airport: $($airport.Count)"
Write-Host "Total: $($urls.Count)"

# Generate JavaScript camera array
$js = "        const cameras = [`n"
$id = 1

# WSDOT Cameras
foreach ($url in $wsdot) {
    $js += "            {id:$id,name:'WSDOT Camera $id',location:'WSDOT Highway',source:'wsdot',url:'$url'},`n"
    $id++
}

# Airport Cameras
foreach ($url in $airport) {
    $js += "            {id:$id,name:'Airport Camera $id',location:'Regional Airport',source:'airport',url:'$url'},`n"
    $id++
}

# Everett Cameras
foreach ($url in $everett) {
    $js += "            {id:$id,name:'Everett Camera $id',location:'City of Everett',source:'everett',url:'$url'},`n"
    $id++
}

# Snohomish Cameras
foreach ($url in $snohomish) {
    $js += "            {id:$id,name:'Snohomish Camera $id',location:'Snohomish County',source:'snohomish',url:'$url'},`n"
    $id++
}

# Remove trailing comma and close array
$js = $js.TrimEnd(",`n") + "`n        ];"

# Save to file
$js | Out-File camera-array.js -Encoding UTF8
Write-Host "Generated: camera-array.js with $id total cameras"
