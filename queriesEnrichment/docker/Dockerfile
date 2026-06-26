# Multi-stage Dockerfile for Azure PowerShell Workbook Audit Container
# Stage 1: Base image with PowerShell and required modules
FROM mcr.microsoft.com/powershell:7.4-ubuntu-22.04 AS base

# Install dependencies
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    git \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Install Azure PowerShell modules
RUN pwsh -Command \
    Install-Module -Name Az.Accounts -Force -AllowClobber && \
    Install-Module -Name Az.ApplicationInsights -Force -AllowClobber && \
    Install-Module -Name Az.Storage -Force -AllowClobber && \
    Install-Module -Name Az.KeyVault -Force -AllowClobber && \
    Install-Module -Name Az.ManagedServiceIdentity -Force -AllowClobber

# Stage 2: Runtime image with script and configuration
FROM base AS runtime

WORKDIR /app

# Copy PowerShell script
COPY scripts/Extract_Queries_From_Deployment_Workbooks.ps1 /app/scripts/
COPY scripts/call_ai_foundry_ext.ps1 /app/scripts/

# Copy configuration
COPY config/config.json /app/config/

# Create output directory
RUN mkdir -p /app/output && chmod 777 /app/output

# Set PowerShell execution policy and entry point
RUN pwsh -Command "Set-PSRepository -Name PSGallery -InstallationPolicy Trusted"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD pwsh -Command "exit 0" || exit 1

# Entry point: Run the main script with Managed Identity
ENTRYPOINT ["pwsh", "-NoProfile", "-Command", \
    "& /app/scripts/Extract_Queries_From_Deployment_Workbooks.ps1"]
