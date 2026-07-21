"""
Azure FinOps Savings Reporting API
Real-time dashboard for Reservation and Savings Plan savings calculations
"""

__version__ = "1.0.0"
__author__ = "FinOps Team"
__description__ = "Real-time Cost Management API for Azure savings reporting"

from .cost_api import CostManagementClient, MonthlySavings, get_savings_summary

__all__ = [
    "CostManagementClient",
    "MonthlySavings",
    "get_savings_summary",
]
