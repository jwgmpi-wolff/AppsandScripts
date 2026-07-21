"""
Unit tests for Cost Management API client.
"""

import pytest
from datetime import datetime
from cost_api import CostManagementClient, MonthlySavings


class TestMonthlySavings:
    """Test MonthlySavings data class."""

    def test_creation(self):
        savings = MonthlySavings(
            month="2026-07",
            reservation_savings=1000.00,
            savings_plan_savings=500.00,
            total_savings=1500.00,
            reservation_quantity=100.0,
            savings_plan_quantity=50.0,
            reservation_effective_price=10.00,
            savings_plan_effective_price=10.00,
        )

        assert savings.month == "2026-07"
        assert savings.total_savings == 1500.00
        assert savings.reservation_savings == 1000.00
        assert savings.savings_plan_savings == 500.00

    def test_total_calculation(self):
        savings = MonthlySavings(
            month="2026-07",
            reservation_savings=1000.00,
            savings_plan_savings=2000.00,
            total_savings=3000.00,
            reservation_quantity=100.0,
            savings_plan_quantity=200.0,
            reservation_effective_price=10.00,
            savings_plan_effective_price=10.00,
        )

        assert savings.total_savings == 3000.00


class TestCostManagementClient:
    """Test Cost Management client."""

    def test_client_initialization(self):
        client = CostManagementClient("test-subscription-id")
        assert client.subscription_id == "test-subscription-id"

    def test_query_build(self):
        client = CostManagementClient("test-sub")
        start = datetime(2026, 7, 1)
        end = datetime(2026, 7, 31)

        query = client._build_query(start, end, ["Reservation"])

        assert query["type"] == "ActualCost"
        assert query["timeframe"] == "Custom"
        assert query["dataset"]["granularity"] == "Monthly"
        assert "filter" in query["dataset"]

    def test_query_build_date_format(self):
        client = CostManagementClient("test-sub")
        start = datetime(2026, 7, 15, 10, 30, 0)
        end = datetime(2026, 7, 20, 15, 45, 0)

        query = client._build_query(start, end)

        assert query["timePeriod"]["from"] == "2026-07-15T00:00:00Z"
        assert query["timePeriod"]["to"] == "2026-07-20T23:59:59Z"

    def test_token_refresh_logic(self):
        """Test token refresh doesn't happen if still valid."""
        client = CostManagementClient("test-sub")

        # Mock token and expiry
        client.token = "test-token"
        client.token_expiry = datetime.now() + datetime.timedelta(minutes=30)

        # Should return cached token
        token = client._get_token()
        assert token == "test-token"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
