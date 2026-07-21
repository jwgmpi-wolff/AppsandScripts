"""
Azure Cost Management API client for real-time FinOps reporting.
Calculates Reservation and Savings Plan savings with current month views.
"""

import json
import logging
import time
import random
from datetime import datetime, timedelta
from typing import Optional, Dict, Any, List
from dataclasses import dataclass, asdict

import requests
from azure.identity import DefaultAzureCredential
from requests.exceptions import RequestException

logger = logging.getLogger(__name__)


@dataclass
class MonthlySavings:
    """Monthly savings calculation results."""
    month: str
    # Actual costs paid (after discounts)
    on_demand_actual_cost: float
    reservation_actual_cost: float
    savings_plan_actual_cost: float
    total_actual_cost: float
    # What would be paid without discounts (list price)
    on_demand_list_price: float
    # Savings breakdown
    reservation_savings: float
    savings_plan_savings: float
    total_savings: float
    # Quantities and effective prices
    reservation_quantity: float
    savings_plan_quantity: float
    reservation_effective_price: float
    savings_plan_effective_price: float


class CostManagementClient:
    """
    Client for Azure Cost Management API.
    Uses Managed Identity or Service Principal via DefaultAzureCredential.
    """

    BASE_URL = "https://management.azure.com"
    API_VERSION = "2023-11-01"

    def __init__(self, subscription_id: str, credential=None, access_token: str = None):
        """
        Initialize Cost Management client.
        
        Args:
            subscription_id: Azure subscription ID
            credential: Azure credential object (defaults to DefaultAzureCredential)
            access_token: Direct access token (optional, bypasses credential)
        """
        self.subscription_id = subscription_id
        self.credential = credential or DefaultAzureCredential()
        self.access_token_override = access_token
        self.token = access_token
        self.token_expiry = None

    def _get_token(self) -> str:
        """Get fresh access token for Cost Management API."""
        # If direct token was provided, use it (unless expired based on simple heuristic)
        if self.access_token_override:
            return self.access_token_override
            
        if self.token and self.token_expiry and datetime.now() < self.token_expiry:
            return self.token

        try:
            token_credential = self.credential.get_token(
                "https://management.azure.com/.default"
            )
            self.token = token_credential.token
            # Token typically valid for ~1 hour, refresh at 55 minutes
            self.token_expiry = datetime.now() + timedelta(minutes=55)
            return self.token
        except Exception as e:
            logger.warning(f"Failed to get token from credential: {e}")
            if self.access_token_override:
                return self.access_token_override
            raise

    def _build_query(
        self,
        start_date: datetime,
        end_date: datetime,
        pricing_models: Optional[List[str]] = None,
        cost_type: str = "ActualCost",
    ) -> Dict[str, Any]:
        """
        Build Cost Management query payload.
        
        Args:
            start_date: Query start date
            end_date: Query end date
            pricing_models: List of pricing models to filter ("Reservation", "SavingsPlan")
            cost_type: "ActualCost" or "AmortizedCost"
        
        Returns:
            Query payload dictionary
        """
        query = {
            "type": cost_type,
            "timeframe": "Custom",
            "timePeriod": {
                "from": start_date.strftime("%Y-%m-%dT00:00:00Z"),
                "to": end_date.strftime("%Y-%m-%dT23:59:59Z"),
            },
            "dataset": {
                "granularity": "Daily",
                "aggregation": {
                    "totalCost": {"name": "CostUSD", "function": "Sum"},
                },
                "grouping": [
                    {"type": "Dimension", "name": "PricingModel"},
                ],
                "sorting": [
                    {"direction": "Ascending", "name": "UsageDate"}
                ]
            },
        }

        # Filter by pricing models if specified
        if pricing_models:
            query["dataset"]["filter"] = {
                "dimensions": {
                    "name": "PricingModel",
                    "operator": "In",
                    "values": pricing_models,
                }
            }

        return query

    def query_costs(
        self,
        start_date: datetime,
        end_date: datetime,
        pricing_models: Optional[List[str]] = None,
        cost_type: str = "ActualCost",
    ) -> Dict[str, Any]:
        """
        Query costs from Cost Management API with exponential backoff retry logic.
        
        Args:
            start_date: Query start date
            end_date: Query end date
            pricing_models: Filter by pricing models
            cost_type: "ActualCost" or "AmortizedCost"
        
        Returns:
            API response with cost data
        
        Raises:
            RequestException: If API call fails after retries
        """
        url = (
            f"{self.BASE_URL}/subscriptions/{self.subscription_id}/"
            f"providers/Microsoft.CostManagement/query?api-version={self.API_VERSION}"
        )

        headers = {
            "Authorization": f"Bearer {self._get_token()}",
            "Content-Type": "application/json",
        }

        payload = self._build_query(start_date, end_date, pricing_models, cost_type)

        logger.info(f"Querying {cost_type} from {start_date} to {end_date}")
        
        # Exponential backoff retry logic
        max_retries = 5
        base_delay = 1  # Start with 1 second
        max_delay = 60  # Cap at 60 seconds
        
        for attempt in range(max_retries):
            try:
                response = requests.post(url, json=payload, headers=headers, timeout=30)
                response.raise_for_status()
                return response.json()
            except requests.exceptions.HTTPError as e:
                if e.response.status_code == 429:  # Rate limit
                    if attempt < max_retries - 1:
                        # Calculate exponential backoff with jitter
                        delay = min(base_delay * (2 ** attempt) + random.uniform(0, 1), max_delay)
                        logger.warning(
                            f"Rate limited (429). Retry {attempt + 1}/{max_retries} "
                            f"in {delay:.1f} seconds..."
                        )
                        time.sleep(delay)
                        # Refresh token before retry
                        self.token_expiry = None
                        continue
                # Non-429 errors or last retry
                logger.error(f"Cost Management API error: {e}")
                raise
            except RequestException as e:
                logger.error(f"Cost Management API error: {e}")
                raise
        
        # Should not reach here, but raise if all retries exhausted
        raise RequestException("Failed to query costs after all retry attempts")

    def get_current_month_savings(self) -> MonthlySavings:
        """
        Calculate savings for current month (month-to-date).
        
        Returns:
            MonthlySavings object with calculated values
        """
        today = datetime.now()
        month_start = datetime(today.year, today.month, 1)
        month_end = today  # MTD (up to today)

        # Query ALL actual costs (no pricing model filter) to get total costs including on-demand
        response = self.query_costs(
            month_start,
            month_end,
            cost_type="ActualCost",
            pricing_models=None,  # Get ALL pricing models
        )

        return self._parse_savings_response(response, month_start, month_end)

    def get_month_savings(self, year: int, month: int) -> MonthlySavings:
        """
        Calculate savings for a specific month.
        
        Args:
            year: Year (e.g., 2026)
            month: Month (1-12)
        
        Returns:
            MonthlySavings object
        """
        month_start = datetime(year, month, 1)
        if month == 12:
            month_end = datetime(year + 1, 1, 1) - timedelta(seconds=1)
        else:
            month_end = datetime(year, month + 1, 1) - timedelta(seconds=1)

        # Query ALL actual costs (no pricing model filter) to get total costs including on-demand
        response = self.query_costs(
            month_start,
            month_end,
            cost_type="ActualCost",
            pricing_models=None,  # Get ALL pricing models
        )

        return self._parse_savings_response(response, month_start, month_end)

    def _parse_savings_response(
        self,
        response: Dict[str, Any],
        start_date: datetime,
        end_date: datetime,
    ) -> MonthlySavings:
        """
        Parse Cost Management API response to calculate savings and costs.
        
        Uses the following logic:
        - AmortizedCost: Shows what you'd pay at list price (on-demand rates)
        - ActualCost: Shows what you actually paid after all discounts applied
        - Savings = AmortizedCost - ActualCost (total discount value)
        
        Args:
            response: API response JSON from ActualCost query
            start_date: Query start date
            end_date: Query end date
        
        Returns:
            MonthlySavings with full cost breakdown
        """
        on_demand_actual = 0.0
        reservation_actual = 0.0
        savings_plan_actual = 0.0
        reservation_qty = 0.0
        savings_plan_qty = 0.0
        total_actual = 0.0  # Total of ALL actual costs paid

        try:
            properties = response.get("properties", {})
            rows = properties.get("rows", [])
            columns = properties.get("columns", [])

            # Map column names to indices
            col_map = {col.get("name"): idx for idx, col in enumerate(columns)}

            # First pass: Sum ALL actual costs to get total
            for row in rows:
                try:
                    cost = float(row[col_map.get("CostUSD", 0)] or 0)
                    total_actual += cost
                    pricing_model = row[col_map.get("PricingModel", 1)] or "Unknown"

                    if pricing_model == "Reservation":
                        reservation_actual += cost
                        reservation_qty += 1
                    elif pricing_model == "SavingsPlan":
                        savings_plan_actual += cost
                        savings_plan_qty += 1
                    else:  # OnDemand or other
                        on_demand_actual += cost
                except (ValueError, IndexError, TypeError) as e:
                    logger.warning(f"Error parsing row {row}: {e}")
                    continue

            # Add delay to avoid rate limiting before next query
            time.sleep(2)
            
            # Query amortized cost to get list price (what everything would cost at on-demand rates)
            amortized_response = self.query_costs(
                start_date,
                end_date,
                cost_type="AmortizedCost"
            )
            
            on_demand_list_price = 0.0
            try:
                amort_props = amortized_response.get("properties", {})
                amort_rows = amort_props.get("rows", [])
                amort_columns = amort_props.get("columns", [])
                amort_col_map = {col.get("name"): idx for idx, col in enumerate(amort_columns)}
                
                for row in amort_rows:
                    try:
                        cost = float(row[amort_col_map.get("CostUSD", 0)] or 0)
                        on_demand_list_price += cost
                    except (ValueError, IndexError, TypeError):
                        continue
            except Exception as e:
                logger.warning(f"Error parsing amortized costs: {e}")
                on_demand_list_price = total_actual
            
            # Fallback: If list price is 0 but we have actual costs, use actual as list price
            # This handles cases where AmortizedCost query returns nothing
            if on_demand_list_price == 0 and total_actual > 0:
                logger.warning(f"AmortizedCost returned 0, using ActualCost as baseline")
                on_demand_list_price = total_actual
            
            # Savings calculation:
            # AmortizedCost shows what EVERYTHING would cost at list price
            # ActualCost shows what was actually paid (after all discounts)
            # Total savings = List Price - Actual Cost
            total_savings = max(0, on_demand_list_price - total_actual)
            
            # For individual breakdowns: show the actual amounts paid with each pricing model
            reservation_savings = max(0, reservation_actual)  # Cost reduction from reservations
            savings_plan_savings = max(0, savings_plan_actual)  # Cost reduction from savings plans

            month_str = start_date.strftime("%Y-%m")

            logger.info(f"Month {month_str}: List={on_demand_list_price}, OnDemand={on_demand_actual}, "
                       f"Reservation={reservation_actual}, SavingsPlan={savings_plan_actual}, "
                       f"Total={total_actual}, Savings={total_savings}")

            return MonthlySavings(
                month=month_str,
                on_demand_actual_cost=round(on_demand_actual, 2),
                reservation_actual_cost=round(reservation_actual, 2),
                savings_plan_actual_cost=round(savings_plan_actual, 2),
                total_actual_cost=round(total_actual, 2),
                on_demand_list_price=round(on_demand_list_price, 2),
                reservation_savings=round(reservation_savings, 2),
                savings_plan_savings=round(savings_plan_savings, 2),
                total_savings=round(total_savings, 2),
                reservation_quantity=reservation_qty,
                savings_plan_quantity=savings_plan_qty,
                reservation_effective_price=round(
                    reservation_actual / max(reservation_qty, 1), 4
                ),
                savings_plan_effective_price=round(
                    savings_plan_actual / max(savings_plan_qty, 1), 4
                ),
            )

        except Exception as e:
            logger.error(f"Error parsing savings response: {e}")
            month_str = start_date.strftime("%Y-%m")
            return MonthlySavings(
                month=month_str,
                on_demand_actual_cost=0.0,
                reservation_actual_cost=0.0,
                savings_plan_actual_cost=0.0,
                total_actual_cost=0.0,
                on_demand_list_price=0.0,
                reservation_savings=0.0,
                savings_plan_savings=0.0,
                total_savings=0.0,
                reservation_quantity=0.0,
                savings_plan_quantity=0.0,
                reservation_effective_price=0.0,
                savings_plan_effective_price=0.0,
            )

    def get_ytd_savings(self) -> Dict[str, MonthlySavings]:
        """
        Get savings for each month year-to-date.
        
        Returns:
            Dictionary of month -> MonthlySavings
        """
        today = datetime.now()
        savings_by_month = {}

        for month in range(1, today.month + 1):
            try:
                # Add delay between month queries to avoid rate limiting
                if savings_by_month:  # Skip delay on first iteration
                    time.sleep(3)
                
                savings = self.get_month_savings(today.year, month)
                savings_by_month[savings.month] = savings
            except Exception as e:
                logger.warning(f"Failed to get savings for {today.year}-{month:02d}: {e}")

        return savings_by_month

    def get_trailing_12_months(self) -> Dict[str, MonthlySavings]:
        """
        Get savings for trailing 12 months.
        
        Returns:
            Dictionary of month -> MonthlySavings
        """
        today = datetime.now()
        savings_by_month = {}

        for i in range(12):
            try:
                # Add delay between month queries to avoid rate limiting
                if savings_by_month:  # Skip delay on first iteration
                    time.sleep(3)
                
                month_date = today - timedelta(days=30 * i)
                savings = self.get_month_savings(month_date.year, month_date.month)
                savings_by_month[savings.month] = savings
            except Exception as e:
                logger.warning(
                    f"Failed to get savings for {month_date.year}-{month_date.month:02d}: {e}"
                )

        return dict(sorted(savings_by_month.items()))


def get_savings_summary(client: CostManagementClient) -> Dict[str, Any]:
    """
    Get comprehensive savings summary for dashboard.
    Limited to current month + Jan/Mar to avoid rate limiting.
    
    Args:
        client: CostManagementClient instance
    
    Returns:
        Dictionary with current month and selected historical metrics
    """
    try:
        # Always get current month
        current_month = client.get_current_month_savings()
        
        # Try to get just a few key months to avoid rate limiting
        ytd_savings = {}
        ytd_savings[current_month.month] = current_month
        
        # Try to get Jan for comparison (usually low rate limit impact)
        try:
            time.sleep(2)
            jan = client.get_month_savings(datetime.now().year, 1)
            ytd_savings[jan.month] = jan
        except Exception as e:
            logger.warning(f"Could not fetch January data: {e}")
        
        # Try March for comparison
        try:
            time.sleep(2)
            mar = client.get_month_savings(datetime.now().year, 3)
            ytd_savings[mar.month] = mar
        except Exception as e:
            logger.warning(f"Could not fetch March data: {e}")

        # Calculate YTD totals from available data
        ytd_total_actual = sum(s.total_actual_cost for s in ytd_savings.values())
        ytd_total_list_price = sum(s.on_demand_list_price for s in ytd_savings.values())
        ytd_total_savings = sum(s.total_savings for s in ytd_savings.values())
        ytd_on_demand_actual = sum(s.on_demand_actual_cost for s in ytd_savings.values())
        ytd_reservation_actual = sum(s.reservation_actual_cost for s in ytd_savings.values())
        ytd_savings_plan_actual = sum(s.savings_plan_actual_cost for s in ytd_savings.values())

        return {
            "current_month": asdict(current_month),
            "ytd_summary": {
                "total_actual_cost": round(ytd_total_actual, 2),
                "total_list_price": round(ytd_total_list_price, 2),
                "total_savings": round(ytd_total_savings, 2),
                "on_demand_actual_cost": round(ytd_on_demand_actual, 2),
                "reservation_actual_cost": round(ytd_reservation_actual, 2),
                "savings_plan_actual_cost": round(ytd_savings_plan_actual, 2),
                "months_tracked": len(ytd_savings),
            },
            "monthly_breakdown": {month: asdict(s) for month, s in ytd_savings.items()},
            "trailing_12_months": {month: asdict(s) for month, s in ytd_savings.items()},
            "query_timestamp": datetime.now().isoformat(),
        }

    except Exception as e:
        logger.error(f"Error generating savings summary: {e}")
        raise


if __name__ == "__main__":
    # Example usage
    logging.basicConfig(level=logging.INFO)

    # Initialize client with your subscription ID
    SUBSCRIPTION_ID = "your-subscription-id-here"
    client = CostManagementClient(SUBSCRIPTION_ID)

    # Get current month savings
    try:
        current = client.get_current_month_savings()
        print(f"Current Month ({current.month}):")
        print(f"  Reservation Savings: ${current.reservation_savings:,.2f}")
        print(f"  Savings Plan Savings: ${current.savings_plan_savings:,.2f}")
        print(f"  Total Savings: ${current.total_savings:,.2f}\n")

        # Get full summary
        summary = get_savings_summary(client)
        print(json.dumps(summary, indent=2))

    except Exception as e:
        print(f"Error: {e}")
