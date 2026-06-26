<!-- Use this file to provide workspace-specific custom instructions to Copilot. For more details, visit https://code.visualstudio.com/docs/copilot/copilot-customization#_use-a-githubcopilotinstructionsmd-file -->

# Kubernetes Container App for Azure PowerShell Workbook Query Audit

## Project Overview
This project containerizes a PowerShell script that audits Azure Monitor Deployment Workbooks across multiple subscriptions. The application:
- Uses Managed Identity for Azure authentication
- Queries Azure Monitor workbooks and extracts KQL/ARG queries
- Calls Azure AI Foundry services for AI-generated query descriptions
- Uploads results (CSV, JSON, HTML) to Azure Storage
- Runs on Kubernetes (AKS) with proper RBAC and secret management

## Workspace Structure
```
queriesEnrichment/
├── docker/                 # Docker image and Dockerfile
├── kubernetes/             # K8s manifests (Deployment, RBAC, ConfigMap, etc.)
├── bicep/                  # Azure IaC (AKS, ACR, Storage, Key Vault, etc.)
├── scripts/                # PowerShell and deployment scripts
├── config/                 # Configuration files and examples
└── README.md               # Main project documentation
```

## Key Requirements
- **Runtime**: PowerShell Core in Docker container
- **Azure Services**: Managed Identity, Key Vault, Storage Account, Application Insights
- **Orchestration**: Kubernetes (AKS recommended)
- **Authentication**: Managed Identity (no secrets in code)
- **External Calls**: Azure AI Foundry API via Azure SDK

## Setup Checklist

- [ ] **Verify Project Structure** - All directories created
- [ ] **Create Docker Image** - PowerShell container with Az modules
- [ ] **Create Kubernetes Manifests** - Deployment, Service, ConfigMap, RBAC
- [ ] **Create Azure IaC (Bicep)** - Resources needed for deployment
- [ ] **Create Deployment Scripts** - Deploy to AKS and Azure Container Registry
- [ ] **Configure Secrets** - Key Vault integration
- [ ] **Create Documentation** - README with deployment instructions
- [ ] **Test Locally** - Docker build and local K8s test

## Development and Deployment
1. Update PowerShell script in `scripts/Extract_Queries_From_Deployment_Workbooks.ps1`
2. Build Docker image: `docker build -f docker/Dockerfile -t workbook-audit:latest .`
3. Deploy to AKS: `kubectl apply -f kubernetes/`
4. Check logs: `kubectl logs -f deployment/workbook-audit-deployment`

## Security Notes
- All sensitive data stored in Azure Key Vault
- Pod uses Managed Identity for Azure authentication
- Container registry credentials managed via Azure
- RBAC configured for least privilege access
