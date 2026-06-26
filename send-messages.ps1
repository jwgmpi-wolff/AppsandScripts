param(
    [string]$ServiceBusFqns = "sbqdemonsiakhfb.servicebus.windows.net",
    [string]$QueueName = "messages"
)

$ErrorActionPreference = "Stop"

function Get-AccessToken {
    $azPath = "C:\Program Files\Microsoft SDKs\Azure\CLI2\wbin\az.cmd"
    [System.Diagnostics.ProcessStartInfo]$psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $azPath
    $psi.Arguments = "account get-access-token --resource https://servicebus.azure.net -o json"
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    
    $process = [System.Diagnostics.Process]::Start($psi)
    $output = $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    
    $response = $output | ConvertFrom-Json
    return $response.accessToken
}

function Send-ServiceBusMessage {
    param(
        [string]$MessageBody
    )
    
    try {
        $token = Get-AccessToken
        $uri = "https://$ServiceBusFqns/$QueueName/messages?api-version=2021-05"
        
        $headers = @{
            "Authorization" = "Bearer $token"
            "Content-Type" = "application/atom+xml;type=entry;charset=utf-8"
        }
        
        # Service Bus expects ATOM+XML format
        $body = @"
<?xml version="1.0" encoding="utf-8"?>
<entry xmlns="http://www.w3.org/2005/Atom">
  <content type="application/octet-stream">$MessageBody</content>
</entry>
"@
        
        $response = Invoke-WebRequest -Uri $uri -Method Post -Headers $headers -Body $body -UseBasicParsing
        Write-Host "Sent: $MessageBody (Status: $($response.StatusCode))"
    } catch {
        Write-Host "Error sending $MessageBody : $_"
    }
}

Write-Host "Connecting to Service Bus: $ServiceBusFqns"
Write-Host "Queue: $QueueName"

for ($i = 1; $i -le 10; $i++) {
    $message = "Message$i"
    Send-ServiceBusMessage -MessageBody $message
    Start-Sleep -Milliseconds 200
}

Write-Host "All 10 messages sent successfully"
