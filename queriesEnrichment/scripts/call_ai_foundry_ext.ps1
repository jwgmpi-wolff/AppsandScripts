<#
.SYNOPSIS
PowerShell module for calling Azure AI Foundry services with OpenAI-compatible API.

.DESCRIPTION
This module provides a wrapper function to call Azure AI Foundry models with proper error handling
and response parsing for use in containerized environments.

.EXAMPLE
$response = call_ai_foundry_ext -ApiKey "your-key" -Endpoint "https://your-endpoint/models" -Prompt "Describe this query"
#>

function Invoke-AiFaundryRequest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$ApiKey,

        [Parameter(Mandatory = $true)]
        [string]$Endpoint,

        [Parameter(Mandatory = $true)]
        [string]$Prompt,

        [Parameter(Mandatory = $false)]
        [int]$MaxTokens = 500,

        [Parameter(Mandatory = $false)]
        [float]$Temperature = 0.7
    )

    try {
        $headers = @{
            "api-key"       = $ApiKey
            "Content-Type"  = "application/json"
        }

        $body = @{
            messages = @(
                @{
                    role    = "system"
                    content = "You are a helpful assistant that describes Azure queries concisely."
                },
                @{
                    role    = "user"
                    content = $Prompt
                }
            )
            max_tokens  = $MaxTokens
            temperature = $Temperature
        } | ConvertTo-Json

        Write-Verbose "Calling AI Foundry endpoint: $Endpoint"

        $response = Invoke-RestMethod -Uri $Endpoint `
            -Method Post `
            -Headers $headers `
            -Body $body `
            -ErrorAction Stop

        if ($response.choices -and $response.choices[0].message.content) {
            return $response.choices[0].message.content
        } else {
            Write-Warning "Unexpected response structure from AI Foundry"
            return "Unable to generate description"
        }
    } catch {
        Write-Warning "AI Foundry API call failed: $($_.Exception.Message)"
        return "Error calling AI service"
    }
}

# Alias for backward compatibility
Set-Alias -Name call_ai_foundry_ext -Value Invoke-AiFaundryRequest -Option AllScope -Force

Export-ModuleMember -Function Invoke-AiFaundryRequest -Alias call_ai_foundry_ext
