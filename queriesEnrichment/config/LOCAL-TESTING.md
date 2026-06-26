# Local Testing Guide

## Prerequisites

Before testing locally, ensure you have:

1. **PowerShell 7+**
   ```powershell
   # Check version
   $PSVersionTable.PSVersion
   ```

2. **Azure CLI**
   ```bash
   az --version
   ```

3. **Azure PowerShell Modules**
   ```powershell
   Install-Module -Name Az.Accounts -Force
   Install-Module -Name Az.ApplicationInsights -Force
   Install-Module -Name Az.Storage -Force
   ```

4. **Docker Desktop** (for Docker testing)
   ```bash
   docker --version
   ```

## Test Scenarios

### Test 1: Authenticate to Azure

```powershell
# Using Managed Identity (if running on Azure VM/AKS)
$context = Connect-AzAccount -Identity

# Or using interactive authentication
Connect-AzAccount

# Verify
Get-AzContext
```

### Test 2: Run PowerShell Script Locally

```powershell
# Navigate to project root
cd queriesEnrichment

# Set environment variables
$env:AZURE_SUBSCRIPTION_ID = "your-subscription-id"
$env:AZURE_RESOURCE_GROUP = "wolffmlrg"
$env:AZURE_KEYVAULT_NAME = "wolffmlkv"
$env:AZURE_AI_ENDPOINT = "https://your-instance.openai.azure.com/"
$env:AZURE_AI_KEY = "your-key"
$env:AZURE_STORAGE_ACCOUNT = "wolffautosa"

# Run the script
.\scripts\Extract_Queries_From_Deployment_Workbooks.ps1

# Check output
ls C:\temp\workbookname_queries.*
```

### Test 3: Test Docker Image Locally

```bash
# Build the Docker image
docker build -f docker/Dockerfile -t workbook-audit:test .

# Run the container
docker run --rm \
  -e AZURE_SUBSCRIPTION_ID="your-subscription-id" \
  -e AZURE_RESOURCE_GROUP="wolffmlrg" \
  -e AZURE_KEYVAULT_NAME="wolffmlkv" \
  -e AZURE_AI_ENDPOINT="https://your-instance.openai.azure.com/" \
  -e AZURE_AI_KEY="your-key" \
  -v $(pwd)/output:/app/output \
  workbook-audit:test

# Check output
ls output/
```

### Test 4: Run in Local Kubernetes (Kind or Minikube)

```bash
# Create a local cluster with Kind
kind create cluster --name workbook-audit-test

# Load Docker image into Kind
kind load docker-image workbook-audit:test --name workbook-audit-test

# Deploy to local cluster
kubectl apply -f kubernetes/00-namespace-and-rbac.yaml
kubectl apply -f kubernetes/01-cronjob-and-deployment.yaml

# Monitor
kubectl logs -f deployment/workbook-audit-deployment -n workbook-audit
```

### Test 5: Validate Bicep Template

```bash
# Validate Bicep syntax
az bicep build --file bicep/main.bicep

# Validate against Azure (dry-run without deployment)
az deployment group validate \
  --resource-group "wolffmlrg" \
  --template-file "bicep/main.bicep" \
  --parameters "bicep/parameters.json"
```

### Test 6: Test AI Foundry Integration

```powershell
# Import the AI Foundry module
Import-Module .\scripts\call_ai_foundry_ext.ps1 -Force

# Test the function
$testPrompt = "Describe a KQL query that retrieves performance data"
$response = Invoke-AiFaundryRequest `
  -ApiKey "your-ai-key" `
  -Endpoint "https://your-instance.openai.azure.com/models" `
  -Prompt $testPrompt

Write-Host "Response: $response"
```

### Test 7: Test Storage Account Upload

```powershell
# Set storage variables
$storageAccountName = "wolffautosa"
$resourceGroup = "wolffmlrg"
$containerName = "graphqueriessamples"
$filePath = "C:\temp\workbookname_queries.csv"

# Get storage context
$storageAccount = Get-AzStorageAccount -ResourceGroupName $resourceGroup -Name $storageAccountName
$context = $storageAccount.Context

# Ensure container exists
$container = Get-AzStorageContainer -Name $containerName -Context $context -ErrorAction SilentlyContinue
if (-not $container) {
    New-AzStorageContainer -Name $containerName -Context $context
}

# Upload file
Set-AzStorageBlobContent -Container $containerName `
  -Blob "test_upload_$(Get-Date -Format 'yyyyMMdd_HHmmss').csv" `
  -File $filePath `
  -Context $context `
  -Force

# Verify
Get-AzStorageBlob -Container $containerName -Context $context
```

## Debugging Tips

### Enable Verbose Logging

```powershell
# Run script with verbose output
$VerbosePreference = "Continue"
$DebugPreference = "Continue"
.\scripts\Extract_Queries_From_Deployment_Workbooks.ps1
```

### Check Permissions

```bash
# Verify Managed Identity can access subscriptions
az account list

# Check Key Vault access
az keyvault secret show --vault-name "wolffmlkv" --name "wolffaipoc2-resource-endpoint"

# Check Storage Account permissions
az storage account show --name "wolffautosa" --resource-group "wolffmlrg"
```

### Monitor with kubectl

```bash
# Watch pod creation
kubectl get pods -n workbook-audit -w

# Get pod details
kubectl describe pod <pod-name> -n workbook-audit

# Check pod events
kubectl get events -n workbook-audit --sort-by='.lastTimestamp'
```

### Inspect Docker Image

```bash
# Inspect layers
docker history workbook-audit:test

# Run interactive shell
docker run -it workbook-audit:test /bin/bash

# Check installed modules
docker run --rm workbook-audit:test pwsh -Command "Get-Module -ListAvailable"
```

## Test Results Checklist

- [ ] Bicep template validates without errors
- [ ] Docker image builds successfully
- [ ] PowerShell script runs without errors
- [ ] Managed Identity authentication works
- [ ] AI Foundry API calls succeed
- [ ] Storage account upload completes
- [ ] Kubernetes deployment starts pods
- [ ] CronJob executes on schedule
- [ ] HTML/CSV/JSON reports are generated
- [ ] Pod logs show no errors

## Performance Baseline

Expected performance on Standard_D2s_v3 nodes:

- **Script execution time**: 5-15 minutes per subscription
- **Number of workbooks**: Varies by subscription (typically 5-20)
- **Queries per workbook**: 2-10 average
- **AI API calls**: ~200ms per query
- **Memory usage**: 200-512 MB
- **CPU usage**: 50-80% (burst during API calls)

## Common Issues and Solutions

| Issue | Solution |
|-------|----------|
| `Connect-AzAccount: The term 'Connect-AzAccount' is not recognized` | Install Azure PowerShell: `Install-Module Az.Accounts -Force` |
| `Docker image push fails` | Verify ACR login: `az acr login --name <acr-name>` |
| `Pod ImagePullBackOff` | Check image exists in ACR: `az acr repository list --name <acr-name>` |
| `AI API returns 401 Unauthorized` | Verify API key in Key Vault: `az keyvault secret show ...` |
| `Storage upload times out` | Check firewall rules: `az storage account show --query networkAcls` |

## Next Steps

After successful local testing:

1. Deploy infrastructure: `make deploy-infra`
2. Build and push image: `make build && make push`
3. Deploy to AKS: `make deploy-k8s`
4. Monitor execution: `make logs`
5. Verify outputs in storage account

---

For more information, see [README.md](../README.md)
