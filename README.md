# Service Bus Queue Demo with Container Apps & KEDA

End-to-end demo showcasing:
- Azure Service Bus messaging
- Container Apps with KEDA autoscaling (0→10 replicas)
- Managed identities (no API keys)
- ACR Tasks for containerized builds
- Application Insights monitoring

## Architecture

```
Producer → Service Bus Queue → Container App (KEDA) → Blob Storage
                                        ↓
                                Application Insights
```

## Prerequisites

- Azure CLI (az) logged in
- Azure Developer CLI (azd) logged in
- No Docker installation needed (ACR Tasks builds on server)

## Deployment

```bash
# 1. Create resource group
az group create --name rg-sbqdemo --location eastus2

# 2. Deploy infrastructure
az deployment group create \
  --resource-group rg-sbqdemo \
  --template-file infra/main.bicep \
  --parameters environmentName=sbqdemo location=eastus2

# 3. Build container image (server-side via ACR)
az acr build \
  --registry <ACR_NAME> \
  --image consumer:latest \
  --file src/consumer/Dockerfile src/consumer

# 4. Update container app with built image
az containerapp update \
  --resource-group rg-sbqdemo \
  --name <CONTAINER_APP_NAME> \
  --image <ACR_LOGIN_SERVER>/consumer:latest

# 5. Grant current user permissions
az role assignment create \
  --role "Azure Service Bus Data Sender" \
  --assignee <YOUR_USER_OBJECT_ID> \
  --scope <SERVICE_BUS_NAMESPACE_ID>

az role assignment create \
  --role "Storage Blob Data Reader" \
  --assignee <YOUR_USER_OBJECT_ID> \
  --scope <STORAGE_ACCOUNT_ID>

# 6. Run producer
python src/producer/send_messages.py

# 7. Verify results
az storage blob list \
  --account-name <STORAGE_ACCOUNT> \
  --container-name processed-messages \
  --auth-mode login -o table
```

## Files

- `src/producer/` - Sends 10 messages to Service Bus
- `src/consumer/` - Processes messages from queue, uploads to Blob Storage
- `infra/` - Bicep infrastructure as code
