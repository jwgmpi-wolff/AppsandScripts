.PHONY: help build push deploy logs clean test validate local-test

# Variables
DOCKER_IMAGE := workbook-audit
DOCKER_TAG := latest
REGISTRY := $(ACR_NAME).azurecr.io
FULL_IMAGE := $(REGISTRY)/$(DOCKER_IMAGE):$(DOCKER_TAG)
NAMESPACE := workbook-audit
RESOURCE_GROUP := wolffmlrg
LOCATION := westus2

help: ## Show this help message
	@echo 'Usage: make [target]'
	@echo ''
	@echo 'Targets:'
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-20s %s\n", $$1, $$2}'

build: ## Build Docker image locally
	@echo "Building Docker image: $(DOCKER_IMAGE):$(DOCKER_TAG)"
	docker build -f docker/Dockerfile -t $(DOCKER_IMAGE):$(DOCKER_TAG) .
	@echo "Build complete"

validate-bicep: ## Validate Bicep template
	@echo "Validating Bicep template..."
	az bicep build --file bicep/main.bicep
	@echo "Bicep validation complete"

deploy-infra: validate-bicep ## Deploy Azure infrastructure with Bicep
	@echo "Deploying Azure infrastructure..."
	pwsh -Command ".\scripts\deploy-infrastructure.ps1 -ResourceGroupName '$(RESOURCE_GROUP)' -Location '$(LOCATION)'"

push: ## Push Docker image to ACR
	@if [ -z "$(ACR_NAME)" ]; then echo "Error: ACR_NAME not set"; exit 1; fi
	@echo "Logging into ACR: $(REGISTRY)"
	az acr login --name $(ACR_NAME)
	@echo "Tagging image as $(FULL_IMAGE)"
	docker tag $(DOCKER_IMAGE):$(DOCKER_TAG) $(FULL_IMAGE)
	@echo "Pushing image to ACR..."
	docker push $(FULL_IMAGE)
	@echo "Push complete"

get-aks-creds: ## Get AKS cluster credentials
	@echo "Getting AKS credentials..."
	az aks get-credentials --resource-group $(RESOURCE_GROUP) --name workbookaudit-aks-prod --overwrite-existing

deploy-k8s: get-aks-creds ## Deploy Kubernetes manifests
	@echo "Applying Kubernetes manifests..."
	kubectl apply -f kubernetes/00-namespace-and-rbac.yaml
	kubectl apply -f kubernetes/01-cronjob-and-deployment.yaml
	@echo "Deployment complete"

deploy: build push deploy-k8s ## Full deployment: build, push, and deploy

logs: ## View application logs
	@echo "Streaming logs..."
	kubectl logs -f deployment/workbook-audit-deployment -n $(NAMESPACE) || true

status: ## Check deployment status
	@echo "=== Namespace ==="
	kubectl get namespaces -l app=workbook-audit
	@echo ""
	@echo "=== Pods ==="
	kubectl get pods -n $(NAMESPACE)
	@echo ""
	@echo "=== CronJobs ==="
	kubectl get cronjobs -n $(NAMESPACE)
	@echo ""
	@echo "=== Jobs ==="
	kubectl get jobs -n $(NAMESPACE)

manual-job: ## Create a manual job run
	@echo "Creating manual job run..."
	kubectl create job --from=cronjob/workbook-audit-cronjob manual-audit-run -n $(NAMESPACE)
	@echo "Job created. View logs with: make logs"

describe-pod: ## Describe the first pod (for troubleshooting)
	@POD=$$(kubectl get pods -n $(NAMESPACE) -o jsonpath='{.items[0].metadata.name}'); \
	echo "Describing pod: $$POD"; \
	kubectl describe pod $$POD -n $(NAMESPACE)

shell: ## Open a shell in the pod
	@POD=$$(kubectl get pods -n $(NAMESPACE) -o jsonpath='{.items[0].metadata.name}'); \
	echo "Opening shell in pod: $$POD"; \
	kubectl exec -it $$POD -n $(NAMESPACE) -- /bin/bash

clean: ## Delete all Kubernetes resources
	@echo "WARNING: This will delete all workbook-audit resources"
	@read -p "Continue? [y/N] " -n 1 -r; \
	echo; \
	if [[ $$REPLY =~ ^[Yy]$$ ]]; then \
		kubectl delete namespace $(NAMESPACE); \
		echo "Resources deleted"; \
	fi

local-test: ## Run PowerShell script locally (for testing)
	@echo "Running PowerShell script locally..."
	pwsh -NoProfile -Command "& '$(PWD)/scripts/Extract_Queries_From_Deployment_Workbooks.ps1'"

docker-test: build ## Test Docker image locally
	@echo "Running Docker image locally..."
	docker run --rm \
		-e AZURE_SUBSCRIPTION_ID="${AZURE_SUBSCRIPTION_ID}" \
		-e AZURE_RESOURCE_GROUP="${AZURE_RESOURCE_GROUP}" \
		-e AZURE_KEYVAULT_NAME="${AZURE_KEYVAULT_NAME}" \
		-e AZURE_AI_ENDPOINT="${AZURE_AI_ENDPOINT}" \
		-e AZURE_AI_KEY="${AZURE_AI_KEY}" \
		-v $$(pwd)/output:/app/output \
		$(DOCKER_IMAGE):$(DOCKER_TAG)

test: ## Run all tests
	@echo "Running tests..."
	@echo "1. Validating Bicep..."
	@make validate-bicep
	@echo "2. Building Docker image..."
	@make build
	@echo "All tests passed!"

format-manifests: ## Format Kubernetes YAML manifests
	@echo "Formatting YAML manifests..."
	@for file in kubernetes/*.yaml; do \
		echo "Formatting $$file"; \
		kubectl apply -f $$file --dry-run=client -o yaml > $${file}.tmp && mv $${file}.tmp $$file; \
	done

version: ## Show component versions
	@echo "=== Version Information ==="
	@echo "Docker: $$(docker --version)"
	@echo "Kubernetes: $$(kubectl version --client --short)"
	@echo "Azure CLI: $$(az --version | head -1)"
	@echo "PowerShell: $$(pwsh --version)"

.PHONY: all
all: help
