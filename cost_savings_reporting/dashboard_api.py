"""
FastAPI server for real-time FinOps dashboard.
Provides REST endpoints for Reservation and Savings Plan savings reporting.
"""

import sys
import os

# Ensure venv packages are on path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from datetime import datetime, timedelta
from typing import Optional, Dict, Any
import logging
from pathlib import Path
import time

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from pydantic import BaseModel

from cost_api import CostManagementClient, get_savings_summary
from azure.identity import InteractiveBrowserCredential
from azure.mgmt.subscription import SubscriptionClient
import requests

# Load environment variables from .env file
load_dotenv()

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="FinOps Savings Reporting API",
    description="Real-time Azure Reservation and Savings Plan savings calculations",
    version="1.0.0",
)

# Enable CORS for dashboard frontend
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Restrict in production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
async def serve_dashboard():
    """Serve the dashboard UI."""
    dashboard_path = Path(__file__).parent / "dashboard.html"
    if dashboard_path.exists():
        return FileResponse(dashboard_path, media_type="text/html")
    else:
        raise HTTPException(
            status_code=404,
            detail="Dashboard UI not found. Make sure dashboard.html exists in the project root."
        )


class SavingsResponse(BaseModel):
    """Savings calculation response with full cost breakdown."""
    month: str
    # Actual costs paid (after all discounts)
    on_demand_actual_cost: float
    reservation_actual_cost: float
    savings_plan_actual_cost: float
    total_actual_cost: float
    # What would be paid at list price (no discounts)
    on_demand_list_price: float
    # Savings breakdown
    reservation_savings: float
    savings_plan_savings: float
    total_savings: float
    # Metrics
    reservation_quantity: float
    savings_plan_quantity: float
    query_timestamp: str


class DashboardSummary(BaseModel):
    """Dashboard summary response."""
    current_month: dict
    ytd_summary: dict
    monthly_breakdown: dict
    query_timestamp: str


# Global client (initialize on startup)
cost_client: Optional[CostManagementClient] = None

# Dashboard cache
dashboard_cache: Dict[str, Any] = {
    "data": None,
    "timestamp": None,
    "ttl_seconds": 300,  # Cache for 5 minutes
}

# Current active subscription
current_subscription: str = os.getenv("AZURE_SUBSCRIPTION_ID", "")

# Store credentials for subscription switching
cached_credential = None


def is_cache_valid() -> bool:
    """Check if dashboard cache is still valid."""
    if dashboard_cache["data"] is None or dashboard_cache["timestamp"] is None:
        return False
    age_seconds = (datetime.now() - dashboard_cache["timestamp"]).total_seconds()
    return age_seconds < dashboard_cache["ttl_seconds"]


@app.on_event("startup")
async def startup_event():
    """Initialize Cost Management client on app startup."""
    global cost_client
    try:
        # Get subscription ID from environment or config
        subscription_id = os.getenv(
            "AZURE_SUBSCRIPTION_ID",
            os.getenv("AZURE_SUBSCRIPTION", ""),
        )
        if not subscription_id:
            raise ValueError(
                "AZURE_SUBSCRIPTION_ID or AZURE_SUBSCRIPTION environment variable required"
            )
        
        # Try to use direct access token if available (for local dev with CLI)
        access_token = os.getenv("AZURE_ACCESS_TOKEN")
        
        cost_client = CostManagementClient(subscription_id, access_token=access_token)
        logger.info(f"Cost Management client initialized for {subscription_id}")
    except Exception as e:
        logger.error(f"Failed to initialize Cost Management client: {e}")
        raise


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "timestamp": datetime.now().isoformat(),
        "client_initialized": cost_client is not None,
        "current_subscription": current_subscription,
    }


@app.post("/auth/login")
async def azure_login():
    """Authenticate with Azure using interactive browser login."""
    global cached_credential
    try:
        # Use interactive browser login
        credential = InteractiveBrowserCredential()
        
        # Get token to verify authentication works
        token = credential.get_token("https://management.azure.com/.default")
        cached_credential = credential
        
        logger.info("Azure authentication successful")
        return {
            "status": "authenticated",
            "message": "Successfully logged in to Azure",
            "timestamp": datetime.now().isoformat(),
        }
    except Exception as e:
        logger.error(f"Azure authentication failed: {e}")
        raise HTTPException(status_code=401, detail=f"Authentication failed: {str(e)}")


@app.get("/auth/subscriptions")
async def list_subscriptions():
    """List all available Azure subscriptions for the authenticated user."""
    try:
        # Use credential from cache or create new one
        credential = cached_credential
        if credential is None:
            credential = InteractiveBrowserCredential()
        
        # Get subscription client
        subscription_client = SubscriptionClient(credential)
        subscriptions = subscription_client.subscriptions.list()
        
        sub_list = []
        for sub in subscriptions:
            sub_list.append({
                "id": sub.subscription_id,
                "display_name": sub.display_name,
                "state": sub.state,
            })
        
        logger.info(f"Found {len(sub_list)} subscriptions")
        return {
            "subscriptions": sub_list,
            "count": len(sub_list),
            "timestamp": datetime.now().isoformat(),
        }
    except Exception as e:
        logger.error(f"Failed to list subscriptions: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to list subscriptions: {str(e)}")


@app.post("/auth/switch-subscription/{subscription_id}")
async def switch_subscription(subscription_id: str):
    """Switch to a different Azure subscription."""
    global current_subscription, cost_client, dashboard_cache
    try:
        # Validate subscription exists
        credential = cached_credential
        if credential is None:
            credential = InteractiveBrowserCredential()
        
        subscription_client = SubscriptionClient(credential)
        target_sub = subscription_client.subscriptions.get(subscription_id)
        
        # Update current subscription
        current_subscription = subscription_id
        os.environ["AZURE_SUBSCRIPTION_ID"] = subscription_id
        
        # Reinitialize cost client with new subscription
        cost_client = CostManagementClient(subscription_id, credential=credential)
        
        # Clear cache
        dashboard_cache["data"] = None
        dashboard_cache["timestamp"] = None
        
        logger.info(f"Switched to subscription: {target_sub.display_name} ({subscription_id})")
        return {
            "status": "success",
            "message": f"Switched to subscription: {target_sub.display_name}",
            "subscription_id": subscription_id,
            "timestamp": datetime.now().isoformat(),
        }
    except Exception as e:
        logger.error(f"Failed to switch subscription: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to switch subscription: {str(e)}")


@app.get("/auth/status")
async def auth_status():
    """Get current authentication and subscription status."""
    return {
        "authenticated": cached_credential is not None,
        "current_subscription": current_subscription,
        "subscription_name": os.getenv("AZURE_SUBSCRIPTION_NAME", "Unknown"),
        "timestamp": datetime.now().isoformat(),
    }


@app.get("/api/current-month", response_model=SavingsResponse)
async def get_current_month():
    """Get current month (month-to-date) savings."""
    if not cost_client:
        raise HTTPException(status_code=500, detail="Cost Management client not initialized")

    try:
        savings = cost_client.get_current_month_savings()
        return SavingsResponse(
            month=savings.month,
            on_demand_actual_cost=savings.on_demand_actual_cost,
            reservation_actual_cost=savings.reservation_actual_cost,
            savings_plan_actual_cost=savings.savings_plan_actual_cost,
            total_actual_cost=savings.total_actual_cost,
            on_demand_list_price=savings.on_demand_list_price,
            reservation_savings=savings.reservation_savings,
            savings_plan_savings=savings.savings_plan_savings,
            total_savings=savings.total_savings,
            reservation_quantity=savings.reservation_quantity,
            savings_plan_quantity=savings.savings_plan_quantity,
            query_timestamp=datetime.now().isoformat(),
        )
    except Exception as e:
        logger.error(f"Error fetching current month savings: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/month/{year}/{month}", response_model=SavingsResponse)
async def get_month(year: int, month: int):
    """Get savings for specific month."""
    if not cost_client:
        raise HTTPException(status_code=500, detail="Cost Management client not initialized")

    if not (1 <= month <= 12):
        raise HTTPException(status_code=400, detail="Month must be 1-12")

    try:
        savings = cost_client.get_month_savings(year, month)
        return SavingsResponse(
            month=savings.month,
            on_demand_actual_cost=savings.on_demand_actual_cost,
            reservation_actual_cost=savings.reservation_actual_cost,
            savings_plan_actual_cost=savings.savings_plan_actual_cost,
            total_actual_cost=savings.total_actual_cost,
            on_demand_list_price=savings.on_demand_list_price,
            reservation_savings=savings.reservation_savings,
            savings_plan_savings=savings.savings_plan_savings,
            total_savings=savings.total_savings,
            reservation_quantity=savings.reservation_quantity,
            savings_plan_quantity=savings.savings_plan_quantity,
            query_timestamp=datetime.now().isoformat(),
        )
    except Exception as e:
        logger.error(f"Error fetching savings for {year}-{month:02d}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/ytd", response_model=DashboardSummary)
async def get_ytd():
    """Get year-to-date savings summary."""
    if not cost_client:
        raise HTTPException(status_code=500, detail="Cost Management client not initialized")

    try:
        summary = get_savings_summary(cost_client)
        return DashboardSummary(**summary)
    except Exception as e:
        logger.error(f"Error fetching YTD summary: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/trailing-12-months")
async def get_trailing_12():
    """Get savings for trailing 12 months."""
    if not cost_client:
        raise HTTPException(status_code=500, detail="Cost Management client not initialized")

    try:
        savings_dict = cost_client.get_trailing_12_months()
        return {
            "months": {
                month: {
                    "reservation_savings": s.reservation_savings,
                    "savings_plan_savings": s.savings_plan_savings,
                    "total_savings": s.total_savings,
                }
                for month, s in savings_dict.items()
            },
            "query_timestamp": datetime.now().isoformat(),
        }
    except Exception as e:
        logger.error(f"Error fetching trailing 12 months: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/dashboard")
async def get_dashboard():
    """Comprehensive dashboard endpoint with all metrics."""
    if not cost_client:
        raise HTTPException(status_code=500, detail="Cost Management client not initialized")

    # Check if cache is valid
    if is_cache_valid():
        logger.info("Returning cached dashboard data (age < 5 minutes)")
        return dashboard_cache["data"]

    try:
        logger.info("Cache miss or expired - querying fresh data")
        # Get YTD summary (optimized to fetch current month + Jan + Mar)
        ytd_summary = get_savings_summary(cost_client)

        response = {
            "current_month": ytd_summary["current_month"],
            "ytd": ytd_summary["ytd_summary"],
            "monthly_breakdown": ytd_summary["monthly_breakdown"],
            "trailing_12_months": ytd_summary["trailing_12_months"],
            "query_timestamp": datetime.now().isoformat(),
        }
        
        # Update cache
        dashboard_cache["data"] = response
        dashboard_cache["timestamp"] = datetime.now()
        logger.info("Dashboard cache updated")
        
        return response
    except Exception as e:
        # If query fails but we have old cache data, return that
        if dashboard_cache["data"] is not None:
            logger.warning(f"Query failed but returning stale cache: {e}")
            return dashboard_cache["data"]
        
        logger.error(f"Error generating dashboard data: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/advisor-recommendations")
async def get_advisor_recommendations():
    """Fetch Azure Advisor recommendations for cost optimization."""
    if not cached_credential:
        raise HTTPException(status_code=401, detail="Not authenticated. Please login first.")
    
    if not current_subscription:
        raise HTTPException(status_code=400, detail="No subscription selected")
    
    try:
        # Get access token
        token = cached_credential.get_token("https://management.azure.com/.default")
        
        # Query Advisor API for recommendations
        advisor_url = (
            f"https://management.azure.com/subscriptions/{current_subscription}"
            f"/providers/Microsoft.Advisor/recommendations?api-version=2020-01-01"
        )
        
        headers = {
            "Authorization": f"Bearer {token.token}",
            "Content-Type": "application/json",
        }
        
        response = requests.get(advisor_url, headers=headers, timeout=30)
        response.raise_for_status()
        
        data = response.json()
        recommendations = data.get("value", [])
        
        # Filter for cost-related recommendations
        cost_recommendations = []
        for rec in recommendations:
            properties = rec.get("properties", {})
            category = properties.get("category", "")
            
            # Filter: Cost, HighAvailability (with reservation keywords)
            if category in ["Cost", "HighAvailability"]:
                short_description = properties.get("shortDescription", {}).get("problem", "")
                
                # Include reservations, savings, and optimization recommendations
                if any(keyword in short_description.lower() for keyword in 
                       ["reservation", "savings", "optimize", "cost", "underutilized", "right-size"]):
                    cost_recommendations.append({
                        "id": rec.get("id", ""),
                        "name": rec.get("name", ""),
                        "category": category,
                        "impact": properties.get("impact", ""),
                        "problem": properties.get("shortDescription", {}).get("problem", ""),
                        "solution": properties.get("shortDescription", {}).get("solution", ""),
                        "estimated_savings": properties.get("extendedProperties", {}).get("savingsAmount", ""),
                        "savings_currency": properties.get("extendedProperties", {}).get("savingsCurrency", ""),
                        "affected_resource": properties.get("resourceMetadata", {}).get("resourceId", ""),
                    })
        
        logger.info(f"Found {len(cost_recommendations)} cost-related Advisor recommendations")
        
        return {
            "recommendations": cost_recommendations,
            "count": len(cost_recommendations),
            "query_timestamp": datetime.now().isoformat(),
        }
    
    except Exception as e:
        logger.error(f"Error fetching Advisor recommendations: {e}")
        # Return empty recommendations instead of failing
        return {
            "recommendations": [],
            "count": 0,
            "error": str(e),
            "query_timestamp": datetime.now().isoformat(),
        }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "dashboard_api:app",
        host="0.0.0.0",
        port=8000,
        reload=False,
        log_level="info",
    )
