#!/usr/bin/env python3
"""
Example usage of Cost Management API client.
Demonstrates current month, YTD, and dashboard queries.
"""

import json
import os
import sys
from datetime import datetime

# Add current directory to path
sys.path.insert(0, os.path.dirname(__file__))

from cost_api import CostManagementClient, get_savings_summary
import logging

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


def print_separator(title: str = ""):
    """Print formatted section separator."""
    if title:
        print(f"\n{'='*60}")
        print(f"  {title}")
        print(f"{'='*60}\n")
    else:
        print(f"{'-'*60}\n")


def example_current_month(client: CostManagementClient):
    """Example: Get current month savings."""
    print_separator("CURRENT MONTH (MTD)")

    current = client.get_current_month_savings()

    print(f"Month:                  {current.month}")
    print(f"Reservation Savings:    ${current.reservation_savings:>15,.2f}")
    print(f"Savings Plan Savings:   ${current.savings_plan_savings:>15,.2f}")
    print(f"─" * 50)
    print(f"Total Savings:          ${current.total_savings:>15,.2f}")
    print(f"\nMetrics:")
    print(f"  Reservation Quantity: {current.reservation_quantity:,.0f} units")
    print(f"  Savings Plan Quantity: {current.savings_plan_quantity:,.0f} units")
    print(
        f"  Avg RI Price:         ${current.reservation_effective_price:>15,.4f}/unit"
    )
    print(
        f"  Avg SP Price:         ${current.savings_plan_effective_price:>15,.4f}/unit"
    )


def example_specific_month(client: CostManagementClient, year: int, month: int):
    """Example: Get savings for specific month."""
    print_separator(f"SPECIFIC MONTH ({year}-{month:02d})")

    savings = client.get_month_savings(year, month)

    print(f"Month:                  {savings.month}")
    print(f"Reservation Savings:    ${savings.reservation_savings:>15,.2f}")
    print(f"Savings Plan Savings:   ${savings.savings_plan_savings:>15,.2f}")
    print(f"─" * 50)
    print(f"Total Savings:          ${savings.total_savings:>15,.2f}")


def example_ytd(client: CostManagementClient):
    """Example: Get year-to-date summary."""
    print_separator("YEAR-TO-DATE SUMMARY")

    ytd = client.get_ytd_savings()

    total_ri = 0
    total_sp = 0

    for month, savings in sorted(ytd.items()):
        total_ri += savings.reservation_savings
        total_sp += savings.savings_plan_savings
        print(f"{month}: RI ${savings.reservation_savings:>12,.2f}  |  "
              f"SP ${savings.savings_plan_savings:>12,.2f}  |  "
              f"Total ${savings.total_savings:>12,.2f}")

    print(f"\n{'─' * 60}")
    print(f"YTD Reservation Savings: ${total_ri:>20,.2f}")
    print(f"YTD Savings Plan Savings: ${total_sp:>20,.2f}")
    print(f"YTD Total Savings:        ${total_ri + total_sp:>20,.2f}")
    print(f"Months Tracked:           {len(ytd):>20}")


def example_dashboard(client: CostManagementClient):
    """Example: Get comprehensive dashboard data."""
    print_separator("DASHBOARD SUMMARY")

    summary = get_savings_summary(client)

    current = summary["current_month"]
    ytd = summary["ytd_summary"]

    print("CURRENT MONTH:")
    print(f"  Reservation Savings:   ${current['reservation_savings']:>15,.2f}")
    print(f"  Savings Plan Savings:  ${current['savings_plan_savings']:>15,.2f}")
    print(f"  Total:                 ${current['total_savings']:>15,.2f}")

    print("\nYEAR-TO-DATE:")
    print(f"  Reservation Savings:   ${ytd['total_reservation_savings']:>15,.2f}")
    print(f"  Savings Plan Savings:  ${ytd['total_savings_plan_savings']:>15,.2f}")
    print(f"  Total:                 ${ytd['total_savings']:>15,.2f}")
    print(f"  Months Tracked:        {ytd['months_tracked']:>15}")

    print("\nMONTHLY BREAKDOWN:")
    for month, data in summary["monthly_breakdown"].items():
        pct = (data["total_savings"] / ytd["total_savings"] * 100
               if ytd["total_savings"] > 0 else 0)
        print(
            f"  {month}: ${data['total_savings']:>12,.2f} ({pct:>5.1f}%)"
        )

    print(f"\nQuery Time: {summary['query_timestamp']}")


def example_trailing_12(client: CostManagementClient):
    """Example: Get trailing 12 months."""
    print_separator("TRAILING 12 MONTHS")

    trailing = client.get_trailing_12_months()

    total_all = 0
    for month, savings in sorted(trailing.items()):
        total_all += savings.total_savings
        print(f"{month}: ${savings.total_savings:>12,.2f}")

    print(f"\n{'─' * 60}")
    print(f"Total (12 months): ${total_all:>35,.2f}")


def main():
    """Run all examples."""
    # Get subscription ID from environment
    subscription_id = os.getenv(
        "AZURE_SUBSCRIPTION_ID",
        os.getenv("AZURE_SUBSCRIPTION", ""),
    )

    if not subscription_id:
        print("ERROR: AZURE_SUBSCRIPTION_ID environment variable not set")
        print("\nSet it with:")
        print("  Windows: set AZURE_SUBSCRIPTION_ID=your-subscription-id")
        print("  Linux/Mac: export AZURE_SUBSCRIPTION_ID=your-subscription-id")
        sys.exit(1)

    print(f"\n{'='*60}")
    print(f"  Azure FinOps Savings Reporting - Examples")
    print(f"  Subscription: {subscription_id[:50]}...")
    print(f"  Query Time: {datetime.now()}")
    print(f"{'='*60}\n")

    try:
        # Initialize client
        client = CostManagementClient(subscription_id)
        logger.info("Cost Management client initialized")

        # Run examples
        example_current_month(client)
        example_specific_month(client, 2026, 6)
        example_ytd(client)
        example_trailing_12(client)
        example_dashboard(client)

        print_separator("EXAMPLES COMPLETE")
        print("✓ All examples completed successfully")

    except Exception as e:
        print_separator("ERROR")
        print(f"✗ Error: {e}")
        logger.exception("Example failed")
        sys.exit(1)


if __name__ == "__main__":
    main()
