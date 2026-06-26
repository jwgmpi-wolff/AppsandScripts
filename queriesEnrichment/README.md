# Kubernetes Container App: Azure PowerShell Workbook Query Audit

This project containerizes a PowerShell script that audits Azure Monitor Deployment Workbooks across multiple subscriptions, extracts KQL and Azure Resource Graph (ARG) queries, enriches them with AI-generated descriptions, and uploads results to Azure Storage.

## 🎯 Overview

The application provides an end-to-end audit pipeline for Azure Monitor workbooks with:

- **Managed Identity Authentication**: Secure, non-interactive authentication for Kubernetes workloads
- **Multi-Subscription Support**: Iterate across all accessible Azure subscriptions
- **AI-Enriched Descriptions**: Automatically generate human-readable descriptions for each query using Azure AI Foundry
- **Multiple Output Formats**: HTML reports, CSV exports, and JSON data
- **Kubernetes-Ready**: CronJob scheduling, RBAC, ConfigMaps, Secrets, and proper pod management
- **Azure Infrastructure as Code**: Complete Bicep templates for deployment

## 📁 Project Structure

```
queriesEnrichment/
├── docker/
│   ├── Dockerfile                    # Multi-stage PowerShell container
│   └── .dockerignore
├── kubernetes/
│   ├── 00-namespace-and-rbac.yaml   # Namespace, ServiceAccount, RBAC, ConfigMap
│   └── 01-cronjob-and-deployment.yaml # CronJob and Deployment specs
├── bicep/
│   ├── main.bicep                    # Infrastructure as Code
│   └── parameters.json               # Bicep parameters
├── scripts/
│   ├── Extract_Queries_From_Deployment_Workbooks.ps1  # Main audit script
│   ├── call_ai_foundry_ext.ps1       # AI Foundry integration module
│   ├── deploy-infrastructure.ps1     # Azure infrastructure deployment
│   └── deploy-to-aks.sh             # Kubernetes deployment
├── config/
│   ├── config.json                   # Application configuration
│   └── .env.example                  # Environment variables template
└── README.md                         # This file
```

## 🚀 Quick Start

### Prerequisites

- Azure CLI (`az`) - [Install](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli)
- kubectl - [Install](https://kubernetes.io/docs/tasks/tools/)
- Docker Desktop or Docker Engine - [Install](https://www.docker.com/products/docker-desktop)
- PowerShell 7+ - [Install](https://learn.microsoft.com/en-us/powershell/scripting/install/installing-powershell)
- An Azure subscription with appropriate permissions

### Step 1: Prepare Azure Infrastructure

#### Option A: Deploy with Bicep (Recommended)

```powershell
# Authenticate to Azure
Connect-AzAccount

# Deploy infrastructure
.\scripts\deploy-infrastructure.ps1 `
    -ResourceGroupName "wolffmlrg" `
    -Location "westus2"
```

This creates:
- **AKS Cluster** with 2 nodes
- **Azure Container Registry** for storing images
- **Storage Account** for audit results
- **Key Vault** for secrets management
- **Managed Identity** for secure pod authentication

#### Option B: Manual Setup

If you prefer manual setup, create the following resources in Azure Portal:
- Resource Group
- AKS Cluster
- Container Registry
- Storage Account
- Key Vault
- Managed Identity

### Step 2: Configure Secrets in Key Vault

```bash
# Set AI Foundry secrets
az keyvault secret set \
  --vault-name "wolffmlkv" \
  --name "wolffaipoc2-resource-endpoint" \
  --value "https://your-ai-instance.openai.azure.com/"

az keyvault secret set \
  --vault-name "wolffmlkv" \
  --name "wolffaipoc2-resource-key1" \
  --value "your-ai-foundry-key"
```

### Step 3: Build and Push Docker Image

```bash
# Build the Docker image
docker build -f docker/Dockerfile -t workbook-audit:latest .

# Login to Azure Container Registry
az acr login --name <your-acr-name>

# Tag and push
docker tag workbook-audit:latest <acr-name>.azurecr.io/workbook-audit:latest
docker push <acr-name>.azurecr.io/workbook-audit:latest
```

### Step 4: Deploy to AKS

```bash
# Get AKS credentials
az aks get-credentials --resource-group "wolffmlrg" --name "workbookaudit-aks-prod"

# Deploy Kubernetes manifests
kubectl apply -f kubernetes/00-namespace-and-rbac.yaml
kubectl apply -f kubernetes/01-cronjob-and-deployment.yaml

# Verify deployment
kubectl get ns workbook-audit
kubectl get pods -n workbook-audit
kubectl get cronjobs -n workbook-audit
```

## 🔧 Configuration

### Environment Variables

Edit `config/config.json` or set environment variables in Kubernetes:

| Variable | Description | Example |
|----------|-------------|---------|
| `AZURE_SUBSCRIPTION_ID` | Azure subscription ID | `12345678-1234-1234-1234-123456789012` |
| `AZURE_RESOURCE_GROUP` | Resource group name | `wolffmlrg` |
| `AZURE_KEYVAULT_NAME` | Key Vault name | `wolffmlkv` |
| `AZURE_AI_ENDPOINT` | AI Foundry endpoint | `https://your-instance.openai.azure.com/` |
| `AZURE_STORAGE_ACCOUNT` | Storage account name | `wolffautosa` |
| `AZURE_STORAGE_CONTAINER` | Blob container name | `graphqueriessamples` |

### Kubernetes ConfigMap

Update the ConfigMap in `kubernetes/00-namespace-and-rbac.yaml`:

```yaml
data:
  AZURE_SUBSCRIPTION_ID: "your-subscription-id"
  AZURE_RESOURCE_GROUP: "wolffmlrg"
  AZURE_STORAGE_ACCOUNT: "wolffautosa"
  # ... other configuration
```

## 📊 Running the Audit

### Manual Execution (One-time)

```bash
# Create a Job from the CronJob template
kubectl create job --from=cronjob/workbook-audit-cronjob manual-audit-run -n workbook-audit

# Monitor execution
kubectl logs -f job/manual-audit-run -n workbook-audit
```

### Scheduled Execution (CronJob)

By default, the CronJob runs at **2:00 AM UTC daily**. To modify the schedule:

Edit `kubernetes/01-cronjob-and-deployment.yaml`:

```yaml
spec:
  schedule: "0 2 * * *"  # Cron format: minute hour day month weekday
```

Common schedule examples:
- `0 * * * *` - Every hour
- `0 0 * * *` - Daily at midnight
- `0 2 * * *` - Daily at 2 AM (default)
- `0 0 * * 0` - Weekly on Sunday at midnight
- `0 0 1 * *` - Monthly on the 1st

### View Job Status

```bash
# List all jobs
kubectl get jobs -n workbook-audit

# View job details
kubectl describe job <job-name> -n workbook-audit

# View pod logs
kubectl logs -f pod/<pod-name> -n workbook-audit

# View CronJob status
kubectl get cronjob/workbook-audit-cronjob -n workbook-audit -o jsonpath='{.status}'
```

## 📈 Monitoring and Logging

### View Application Logs

```bash
# Real-time logs
kubectl logs -f deployment/workbook-audit-deployment -n workbook-audit

# View historical logs (last 100 lines)
kubectl logs -n workbook-audit deployment/workbook-audit-deployment --tail=100

# View logs from a specific pod
kubectl logs -f pod/workbook-audit-deployment-abc123-xyz789 -n workbook-audit
```

### Health Check

The container includes a health check endpoint. Verify pod health:

```bash
kubectl get pods -n workbook-audit -o wide
```

### Application Insights Integration

Logs are automatically sent to Log Analytics if monitoring addon is enabled in AKS.

## 📦 Output Files

The script generates three output files in `/app/output/`:

### 1. HTML Report (`workbookname_queries.html`)

Interactive report with:
- Subscription name
- Workbook name
- Query type and resource type
- Query content
- AI-generated description

### 2. CSV Export (`workbookname_queries.csv`)

Spreadsheet-friendly format with columns:
- SubscriptionName
- WorkbookName
- Version
- Title
- QueryType
- ResourceType
- Query
- Description

### 3. JSON Data (`workbookname_queries.json`)

Structured JSON for programmatic access:

```json
{
  "subscriptionName": "mysubscription",
  "workbookName": "MyWorkbook",
  "title": "Query Title",
  "queryType": "KQL",
  "query": "Perf | ...",
  "description": "AI-generated description"
}
```

Files are uploaded to Azure Storage automatically if configured.

## 🔐 Security Best Practices

### Managed Identity

- ✅ **Pod Authentication**: Uses Managed Identity instead of hardcoded credentials
- ✅ **RBAC Least Privilege**: ServiceAccount with minimal permissions
- ✅ **Key Vault Access**: Secrets retrieved securely from Key Vault

### Network Security

- ✅ **Network Policy**: Azure network policies restrict pod-to-pod communication
- ✅ **Storage Firewall**: Storage account configured with network rules
- ✅ **Key Vault Firewall**: Key Vault restricted to Azure services

### Container Security

- ✅ **Non-Root User**: Container runs as uid 1000 (non-privileged)
- ✅ **Read-Only Filesystem**: Root filesystem mounted read-only where possible
- ✅ **Security Capabilities**: Unnecessary Linux capabilities dropped

### Secrets Management

- ✅ **Never store secrets in ConfigMaps**: Use Secrets or Key Vault
- ✅ **Azure Key Vault Integration**: Automatic secret rotation
- ✅ **HTTPS Only**: All Azure service communications encrypted

## 🐛 Troubleshooting

### Pod Fails to Start

```bash
# Check pod events
kubectl describe pod <pod-name> -n workbook-audit

# Check pod logs
kubectl logs <pod-name> -n workbook-audit

# Check RBAC permissions
kubectl auth can-i --list --as=system:serviceaccount:workbook-audit:workbook-audit-sa -n workbook-audit
```

**Common Issues:**
- **ImagePullBackOff**: ACR credentials not configured
  - Solution: Ensure ACR login credentials are correct

- **CrashLoopBackOff**: Script error or missing dependency
  - Solution: Check logs with `kubectl logs` and verify Key Vault access

- **Insufficient Permissions**: Managed Identity lacks required permissions
  - Solution: Verify Managed Identity role assignments

### AI Foundry Errors

```bash
# Verify Key Vault secret
az keyvault secret show --vault-name "wolffmlkv" --name "wolffaipoc2-resource-endpoint"

# Test AI endpoint connectivity
curl -H "api-key: YOUR_KEY" https://your-endpoint/models
```

### Storage Upload Issues

```bash
# Verify storage account access
az storage account show --name "wolffautosa" --resource-group "wolffmlrg"

# List blob containers
az storage container list --account-name "wolffautosa"
```

## 📚 Resources

- [Azure Kubernetes Service (AKS)](https://learn.microsoft.com/en-us/azure/aks/)
- [Azure Monitor Workbooks](https://learn.microsoft.com/en-us/azure/azure-monitor/visualize/workbooks-overview)
- [PowerShell Module for Azure](https://learn.microsoft.com/en-us/powershell/azure/)
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Bicep Language](https://learn.microsoft.com/en-us/azure/azure-resource-manager/bicep/overview)

## 📝 License

MIT License - See LICENSE file for details

## 👤 Author

Jerry Wolff  
Azure Solutions Architect

## 🤝 Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## 📞 Support

For issues and questions:
- Check the [Troubleshooting](#-troubleshooting) section
- Review Azure documentation
- Contact the engineering team

---

**Last Updated**: April 2, 2026  
**Version**: 2.0  
**Status**: Production Ready
