#!/bin/bash
# Quick start script for FinOps API

set -e

echo "=================================="
echo "  Azure FinOps Reporting API"
echo "  Quick Start Setup"
echo "=================================="
echo ""

# Check Python
if ! command -v python3 &> /dev/null; then
    echo "ERROR: Python 3.10+ not found"
    exit 1
fi

PYTHON_VERSION=$(python3 -c 'import sys; print(".".join(map(str, sys.version_info[:2])))')
echo "✓ Python $PYTHON_VERSION detected"

# Create virtual environment
if [ ! -d "venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv venv
fi

# Activate venv
source venv/bin/activate || . venv/Scripts/activate

echo "✓ Virtual environment activated"

# Install dependencies
echo "Installing dependencies..."
pip install -q -r requirements.txt
echo "✓ Dependencies installed"

# Check Azure auth
echo ""
echo "Checking Azure authentication..."
if ! az account show &> /dev/null; then
    echo "⚠ Not logged into Azure CLI"
    echo "  Run: az login"
    echo "  Then: az account set --subscription <subscription-id>"
else
    CURRENT_SUB=$(az account show -o tsv --query id)
    echo "✓ Logged in to subscription: $CURRENT_SUB"
fi

# Setup environment
if [ ! -f ".env" ]; then
    echo ""
    echo "Creating .env file from template..."
    cp .env.example .env
    echo "⚠ Please edit .env with your AZURE_SUBSCRIPTION_ID"
fi

# Create __init__.py for tests
mkdir -p tests
touch tests/__init__.py

echo ""
echo "✓ Setup complete!"
echo ""
echo "Next steps:"
echo "  1. Edit .env with your AZURE_SUBSCRIPTION_ID"
echo "  2. Run examples: python examples.py"
echo "  3. Start API:   uvicorn dashboard_api:app --reload"
echo "  4. Visit docs:  http://localhost:8000/docs"
echo ""
