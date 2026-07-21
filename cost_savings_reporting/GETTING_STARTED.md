# Getting Started: Azure FinOps Savings Reporting API

Complete guide to deploying and using the real-time Cost Management API dashboard.

## Table of Contents
1. [Local Development](#local-development)
2. [Azure Deployment](#azure-deployment)
3. [Configuration](#configuration)
4. [Testing](#testing)
5. [Troubleshooting](#troubleshooting)

---

## Local Development

### Step 1: Prerequisites

- **Python 3.10+**: [Download here](https://www.python.org/downloads/)
- **Azure CLI**: [Install Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli)
- **Azure Subscription** with Reservations or Savings Plans

### Step 2: Clone and Setup

**Windows:**
```bash
cd c:\.git\cost_savings_reporting
setup.bat
```

**Linux/macOS:**
```bash
cd c:/.git/cost_savings_reporting
chmod +x setup.sh
./setup.sh
```

**Manual Setup:**
```bash
# Create virtual environment
python -m venv venv

# Activate (Windows)
venv\Scripts\activate

# Activate (Linux/macOS)
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt
```

### Step 3: Authenticate with Azure

**Option A: Azure CLI (Recommended for local dev)**
```bash
az login
az account set --subscription <your-subscription-id>
```

The app will auto-detect your Azure CLI credentials via `DefaultAzureCredential`.

**Option B: Service Principal**
```bash
# Create service principal
az ad sp create-for-rbac --name FinOpsReportingAgent --role "Cost Management Reader" \
  --scopes /subscriptions/<subscription-id>
```

Copy output and add to `.env`:
```env
AZURE_CLIENT_ID=<appId>
AZURE_CLIENT_SECRET=<password>
AZURE_TENANT_ID=<tenant>
AZURE_SUBSCRIPTION_ID=<subscription-id>
```

### Step 4: Configure Subscription ID

Edit `.env`:
```bash
cp .env.example .env
# Edit .env with your AZURE_SUBSCRIPTION_ID
```

### Step 5: Run Examples

Test the API client locally:

```bash
python examples.py
```

Output:
```
============================================================
  Azure FinOps Savings Reporting - Examples
  Subscription: 12345678-1234-1234-1234-123456789012...
  Query Time: 2026-07-21 15:30:00.123456
============================================================

============================================================
  CURRENT MONTH (MTD)
============================================================

Month:                  2026-07
Reservation Savings:        15450.25
Savings Plan Savings:        8200.50
──────────────────────────────────────────────────────
Total Savings:              23650.75
...
```

### Step 6: Start the API

**Development (with auto-reload):**
```bash
uvicorn dashboard_api:app --reload
```

**Production:**
```bash
uvicorn dashboard_api:app --host 0.0.0.0 --port 8000 --workers 4
```

### Step 7: Test the API

Open browser to http://localhost:8000/docs

Or test with cURL:
```bash
# Health check
curl http://localhost:8000/health

# Get current month
curl http://localhost:8000/api/current-month

# Get full dashboard
curl http://localhost:8000/api/dashboard
```

---

## Azure Deployment

### Deployment Option 1: Azure Container Apps (Recommended)

**Prerequisites:**
- Azure CLI
- Container Registry (optional)
- Service Principal with Cost Management Reader role

**Steps:**

1. **Create service principal with RBAC:**
```bash
# Get your subscription ID
SUBID=$(az account show --query id -o tsv)

# Create service principal
SP=$(az ad sp create-for-rbac --name FinOpsReportingAgent --role "Cost Management Reader" \
  --scopes /subscriptions/$SUBID)

# Save credentials
CLIENTID=$(echo $SP | jq -r '.appId')
CLIENTSECRET=$(echo $SP | jq -r '.password')
TENANTID=$(echo $SP | jq -r '.tenant')
```

2. **Deploy to Container Apps:**
```bash
# Set variables
APP_NAME=finops-api
RESOURCE_GROUP=FinOps
LOCATION=eastus

# Create resource group
az group create --name $RESOURCE_GROUP --location $LOCATION

# Deploy container app
az containerapp up \
  --name $APP_NAME \
  --resource-group $RESOURCE_GROUP \
  --location $LOCATION \
  --environment-variables \
    AZURE_SUBSCRIPTION_ID=$SUBID \
    AZURE_CLIENT_ID=$CLIENTID \
    AZURE_CLIENT_SECRET=$CLIENTSECRET \
    AZURE_TENANT_ID=$TENANTID \
  --source .
```

3. **Access the API:**
```bash
# Get URL
az containerapp show --name $APP_NAME --resource-group $RESOURCE_GROUP \
  --query properties.configuration.ingress.fqdn -o tsv

# Test
curl https://<your-app>.eastus.azurecontainerapps.io/health
```

### Deployment Option 2: Azure App Service

1. **Create App Service:**
```bash
az appservice plan create --name FinOpsApiPlan --resource-group $RESOURCE_GROUP --sku B2 --is-linux
az webapp create --resource-group $RESOURCE_GROUP --plan FinOpsApiPlan --name $APP_NAME --runtime "PYTHON:3.10"
```

2. **Deploy code:**
```bash
az webapp deployment source config-zip --resource-group $RESOURCE_GROUP \
  --name $APP_NAME --src deployment.zip
```

3. **Configure environment:**
```bash
az webapp config appsettings set --resource-group $RESOURCE_GROUP \
  --name $APP_NAME \
  --settings \
    AZURE_SUBSCRIPTION_ID=$SUBID \
    AZURE_CLIENT_ID=$CLIENTID \
    AZURE_CLIENT_SECRET=$CLIENTSECRET \
    AZURE_TENANT_ID=$TENANTID
```

### Deployment Option 3: Docker + Azure Container Registry

1. **Create ACR:**
```bash
REGISTRY_NAME=finopsregistry
az acr create --resource-group $RESOURCE_GROUP --name $REGISTRY_NAME --sku Basic
```

2. **Build and push:**
```bash
az acr build --registry $REGISTRY_NAME --image finops-api:latest .
```

3. **Deploy to Container Instances:**
```bash
az container create \
  --resource-group $RESOURCE_GROUP \
  --name finops-api \
  --image $REGISTRY_NAME.azurecr.io/finops-api:latest \
  --environment-variables \
    AZURE_SUBSCRIPTION_ID=$SUBID \
    AZURE_CLIENT_ID=$CLIENTID \
    AZURE_CLIENT_SECRET=$CLIENTSECRET \
    AZURE_TENANT_ID=$TENANTID \
  --ports 8000 \
  --ip-address public
```

---

## Configuration

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `AZURE_SUBSCRIPTION_ID` | ✓ | Azure subscription ID |
| `AZURE_CLIENT_ID` | ✗ | Service principal client ID |
| `AZURE_CLIENT_SECRET` | ✗ | Service principal secret |
| `AZURE_TENANT_ID` | ✗ | Microsoft Entra tenant ID |
| `API_HOST` | ✗ | API listen host (default: 0.0.0.0) |
| `API_PORT` | ✗ | API listen port (default: 8000) |

### RBAC Permissions

Your identity needs **Cost Management Reader** role:

```bash
az role assignment create \
  --assignee <principal-id> \
  --role "Cost Management Reader" \
  --scope /subscriptions/<subscription-id>
```

Find your principal ID:
```bash
# If using CLI login
az ad signed-in-user show --query id -o tsv

# If using service principal
az ad sp show --id <client-id> --query id -o tsv
```

---

## Testing

### Unit Tests
```bash
pytest tests/ -v
```

### Integration Tests (requires Azure auth)
```bash
pytest tests/test_cost_api.py::TestCostManagementClient -v
```

### API Smoke Tests
```bash
# Start API first
uvicorn dashboard_api:app &

# Test endpoints
curl -f http://localhost:8000/health
curl -f http://localhost:8000/api/current-month
curl -f http://localhost:8000/api/ytd

# Kill server
kill %1
```

---

## Troubleshooting

### "Cost Management client not initialized"
**Cause**: `AZURE_SUBSCRIPTION_ID` not set or authentication failed

**Solution:**
```bash
# Check environment variable
echo $AZURE_SUBSCRIPTION_ID  # Linux/macOS
echo %AZURE_SUBSCRIPTION_ID% # Windows

# Set it
export AZURE_SUBSCRIPTION_ID=your-sub-id  # Linux/macOS
set AZURE_SUBSCRIPTION_ID=your-sub-id     # Windows

# Verify Azure auth
az account show
```

### "Unauthorized: 401"
**Cause**: Identity lacks Cost Management Reader role

**Solution:**
```bash
# Get your principal ID
PRINCIPAL_ID=$(az ad signed-in-user show --query id -o tsv)

# Assign role
az role assignment create \
  --assignee $PRINCIPAL_ID \
  --role "Cost Management Reader" \
  --scope /subscriptions/$(az account show --query id -o tsv)

# Wait 2-3 minutes, then retry
```

### "No data returned"
**Cause**: No Reservations or Savings Plans in subscription, or wrong date range

**Solution:**
```bash
# Check if you have RIs/SPs
az reservations reservation list --query "length([*])"

# Verify they have usage in the date range
az costmanagement query --name "ExampleQuery" \
  --timeframe "MonthToDate" \
  --type "ActualCost"
```

### Connection timeout
**Cause**: Cost Management API is slow or network issue

**Solution:**
- Increase timeout: Edit `cost_api.py`, line 98: `requests.post(..., timeout=60)`
- Check network: `curl https://management.azure.com/` should respond

### "Token refresh failed"
**Cause**: Credentials expired or invalid

**Solution:**
```bash
# Re-authenticate
az login

# Or refresh service principal:
az ad sp credential reset --id <client-id>
```

---

## Next Steps

1. **Integrate with BI tools:**
   - Power BI: [Direct API endpoint](https://www.powerbiguru.com/rest-api/)
   - Tableau: [Web data connector](https://help.tableau.com/current/server/en-us/datasource_web.htm)
   - Grafana: [API plugin](https://grafana.com/grafana/plugins/grafana-http-api-datasource/)

2. **Add caching:**
   - Redis: Cache monthly data for 4 hours
   - In-memory: Use `@cache` decorator

3. **Enable dashboards:**
   - Deploy frontend (React/Vue)
   - Connect to `/api/dashboard` endpoint
   - Auto-refresh every 1 hour

4. **Set up monitoring:**
   - Application Insights
   - Alert on API errors
   - Track query latency

---

## Support

- **Documentation**: [README.md](README.md)
- **Examples**: [examples.py](examples.py)
- **Issues**: Check [Troubleshooting](#troubleshooting)
- **Microsoft Docs**: [Cost Management API](https://learn.microsoft.com/en-us/rest/api/cost-management/)
