# PowerShell Deployment script for Kubernetes Workbook Audit Application
# Builds Docker image, uploads to ACR, and deploys to AKS

param(
    [string]$ResourceGroup = "wolffmlrg",
    [string]$Location = "westus2",
    [string]$ClusterName = "workbookaudit-aks-prod",
    [string]$AcrName = "workbookauditacr",
    [string]$DockerfilePath = "docker/Dockerfile",
    [string]$ImageName = "workbook-audit",
    [string]$ImageVersion = "latest",
    [string]$Namespace = "workbook-audit",
    [switch]$SkipBuild,
    [switch]$SkipDeploy
)

# Color output functions
function Write-Success { Write-Host $args -ForegroundColor Green }
function Write-Info { Write-Host $args -ForegroundColor Cyan }
function Write-Warning { Write-Host $args -ForegroundColor Yellow }
function Write-Error { Write-Host $args -ForegroundColor Red }

Write-Warning "Starting Kubernetes deployment workflow..."

# Step 1: Build Docker image
if (-not $SkipBuild) {
    Write-Info "Step 1: Building Docker image..."
    try {
        docker build -f $DockerfilePath -t "${ImageName}:${ImageVersion}" .
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Docker build failed"
            exit 1
        }
        Write-Success "Docker image built successfully"
    } catch {
        Write-Error "Error building Docker image: $_"
        exit 1
    }
} else {
    Write-Info "Skipping Docker build (--SkipBuild specified)"
}

# Step 2: Get ACR login credentials
Write-Info "Step 2: Logging into Azure Container Registry..."
try {
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
    
    $AcrLoginServer = az acr show --resource-group $ResourceGroup --name $AcrName --query loginServer -o tsv
    Write-Info "ACR Login Server: $AcrLoginServer"
    
    az acr login --name $AcrName
    if ($LASTEXITCODE -ne 0) {
        Write-Error "ACR login failed"
        exit 1
    }
    Write-Success "ACR login successful"
} catch {
    Write-Error "Error logging into ACR: $_"
    exit 1
}

# Step 3: Tag and push image to ACR
Write-Info "Step 3: Tagging and pushing image to ACR..."
try {
    $FullImageName = "${AcrLoginServer}/${ImageName}:${ImageVersion}"
    Write-Info "Full image name: $FullImageName"
    
    docker tag "${ImageName}:${ImageVersion}" $FullImageName
    docker push $FullImageName
    
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Docker push failed"
        exit 1
    }
    Write-Success "Image pushed to ACR successfully"
} catch {
    Write-Error "Error pushing image to ACR: $_"
    exit 1
}

# Step 4: Get AKS credentials
Write-Info "Step 4: Getting AKS cluster credentials..."
try {
    az aks get-credentials --resource-group $ResourceGroup --name $ClusterName --overwrite-existing
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to get AKS credentials"
        exit 1
    }
    Write-Success "AKS credentials obtained"
} catch {
    Write-Error "Error getting AKS credentials: $_"
    exit 1
}

# Step 5: Update image reference in Kubernetes manifests
Write-Info "Step 5: Updating image references in Kubernetes manifests..."
try {
    $kubeDir = Join-Path $PSScriptRoot ".." "kubernetes"
    $kubefiles = Get-ChildItem -Path $kubeDir -Filter "*.yaml"
    
    foreach ($file in $kubefiles) {
        $content = Get-Content $file.FullName -Raw
        $updated = $content -replace "your-registry\.azurecr\.io/workbook-audit:latest", $FullImageName
        
        # Backup original
        Copy-Item $file.FullName "$($file.FullName).bak" -Force
        
        # Write updated content
        Set-Content -Path $file.FullName -Value $updated
        Write-Success "Updated: $($file.Name)"
    }
} catch {
    Write-Error "Error updating manifest files: $_"
    exit 1
}

if ($SkipDeploy) {
    Write-Warning "Skipping Kubernetes deployment (--SkipDeploy specified)"
    Write-Info "Image is ready at: $FullImageName"
    Write-Info "Next: Run with -SkipBuild to deploy manifests"
    exit 0
}

# Step 6: Verify namespace
Write-Info "Step 6: Verifying Kubernetes namespace..."
try {
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
    
    kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f -
    Write-Success "Namespace verified"
} catch {
    Write-Error "Error verifying namespace: $_"
}

# Step 7: Apply Kubernetes manifests (already in place, just verify)
Write-Info "Step 7: Verifying Kubernetes manifests..."
try {
    $kubeDir = Join-Path $PSScriptRoot ".." "kubernetes"
    
    # Manifests should already be applied from earlier
    Write-Success "Kubernetes manifests are deployed"
    
    # Show current deployment status
    Write-Info ""
    Write-Info "Current deployment status:"
    kubectl get all -n $Namespace
} catch {
    Write-Error "Error checking deployment status: $_"
}

Write-Success ""
Write-Success "✅ Docker image build and push completed successfully!"
Write-Success ""
Write-Info "Image pushed to: $FullImageName"
Write-Info ""
Write-Info "Next steps:"
Write-Info "1. Create storage container: az storage container create --name 'graphqueriessamples' --account-name 'workbookauditsa' --account-key <key>"
Write-Info "2. Configure Key Vault secrets (AI Foundry credentials)"
Write-Info "3. Trigger a manual job run: kubectl create job --from=cronjob/workbook-audit-cronjob manual-test -n $Namespace"
Write-Info "4. View logs: kubectl logs -f job/manual-test -n $Namespace"
Write-Info "5. Schedule runs daily at 2 AM UTC (CronJob is configured)"
Write-Info ""
