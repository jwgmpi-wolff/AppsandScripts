#!/bin/bash
# Deployment script for Kubernetes Workbook Audit Application
# This script builds the Docker image, uploads to ACR, and deploys to AKS

set -e

# Configuration
RESOURCE_GROUP="wolffmlrg"
LOCATION="westus2"
CLUSTER_NAME="workbookaudit-aks-prod"
ACR_NAME="workbookauditacr"
DOCKERFILE_PATH="docker/Dockerfile"
IMAGE_NAME="workbook-audit"
IMAGE_VERSION="latest"
NAMESPACE="workbook-audit"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Starting Kubernetes deployment workflow...${NC}"

# Step 1: Build Docker image
echo -e "${GREEN}Step 1: Building Docker image...${NC}"
docker build -f "$DOCKERFILE_PATH" -t "$IMAGE_NAME:$IMAGE_VERSION" .
if [ $? -ne 0 ]; then
    echo -e "${RED}Docker build failed${NC}"
    exit 1
fi
echo -e "${GREEN}Docker image built successfully${NC}"

# Step 2: Get ACR login credentials
echo -e "${GREEN}Step 2: Logging into Azure Container Registry...${NC}"
ACR_LOGIN_SERVER=$(az acr show --resource-group "$RESOURCE_GROUP" --name "$ACR_NAME" --query loginServer -o tsv)
echo "ACR Login Server: $ACR_LOGIN_SERVER"

az acr login --name "$ACR_NAME"
if [ $? -ne 0 ]; then
    echo -e "${RED}ACR login failed${NC}"
    exit 1
fi

# Step 3: Tag and push image to ACR
echo -e "${GREEN}Step 3: Tagging and pushing image to ACR...${NC}"
FULL_IMAGE_NAME="$ACR_LOGIN_SERVER/$IMAGE_NAME:$IMAGE_VERSION"
docker tag "$IMAGE_NAME:$IMAGE_VERSION" "$FULL_IMAGE_NAME"
docker push "$FULL_IMAGE_NAME"
if [ $? -ne 0 ]; then
    echo -e "${RED}Docker push failed${NC}"
    exit 1
fi
echo -e "${GREEN}Image pushed to ACR${NC}"

# Step 4: Get AKS credentials
echo -e "${GREEN}Step 4: Getting AKS cluster credentials...${NC}"
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
if [ $? -ne 0 ]; then
    echo -e "${RED}Failed to get AKS credentials${NC}"
    exit 1
fi

# Step 5: Create namespace if it doesn't exist
echo -e "${GREEN}Step 5: Creating Kubernetes namespace...${NC}"
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

# Step 6: Update image reference in Kubernetes manifests
echo -e "${GREEN}Step 6: Updating image references...${NC}"
sed -i.bak "s|your-registry.azurecr.io/workbook-audit:latest|$FULL_IMAGE_NAME|g" kubernetes/*.yaml

# Step 7: Apply Kubernetes manifests
echo -e "${GREEN}Step 7: Applying Kubernetes manifests...${NC}"
kubectl apply -f kubernetes/00-namespace-and-rbac.yaml
kubectl apply -f kubernetes/01-cronjob-and-deployment.yaml
if [ $? -ne 0 ]; then
    echo -e "${RED}Kubernetes deployment failed${NC}"
    exit 1
fi

# Step 8: Verify deployment
echo -e "${GREEN}Step 8: Verifying deployment...${NC}"
kubectl get namespace "$NAMESPACE"
kubectl get deployments -n "$NAMESPACE"
kubectl get cronjobs -n "$NAMESPACE"

# Step 9: Check pod status
echo -e "${GREEN}Step 9: Checking pod status (waiting 30 seconds)...${NC}"
sleep 30
kubectl get pods -n "$NAMESPACE"

echo -e "${GREEN}Deployment completed successfully!${NC}"
echo ""
echo "Next steps:"
echo "1. View pod logs: kubectl logs -f deployment/workbook-audit-deployment -n $NAMESPACE"
echo "2. Port forward (if needed): kubectl port-forward -n $NAMESPACE svc/workbook-audit-service 8080:80"
echo "3. Check job status: kubectl get jobs -n $NAMESPACE"
echo ""
