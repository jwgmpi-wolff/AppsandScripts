# Resource-Level Discount Details Feature 🎯

## Feature Summary
Added resource-level visibility into where Reservations and Savings Plans were applied across your Azure infrastructure. Users can now view detailed breakdowns showing which specific resources benefited from each discount type.

## What's New

### 1. **Backend Enhancement (cost_api.py)**
- **New Data Model**: `ResourceSavingsDetail` dataclass
  ```
  Fields: resource_id, resource_name, resource_type, pricing_model, 
          actual_cost, list_price, savings, quantity, effective_price, 
          region, resource_group
  ```

- **New Methods**:
  - `_extract_resource_details(response, start_date, end_date, pricing_model_filter)` 
    - Parses Azure Cost Management API responses
    - Groups resources by ID and pricing model
    - Calculates savings per resource
    - Returns sorted list (highest savings first)
  
  - `get_reservation_details(year, month)`
    - Returns resource-level breakdown for Reservation pricing model
  
  - `get_savings_plan_details(year, month)`
    - Returns resource-level breakdown for Savings Plan pricing model

### 2. **API Endpoints (dashboard_api.py)**

**Endpoint 1**: `GET /api/reservation-details/{year}/{month}`
```json
{
  "month": "2026-07",
  "pricing_model": "Reservation",
  "resources": [
    {
      "resource_id": "/subscriptions/.../VM001",
      "resource_name": "VM001",
      "resource_type": "Microsoft.Compute/virtualMachines",
      "actual_cost": 245.50,
      "list_price": 350.00,
      "savings": 104.50,
      "quantity": 31,
      "effective_price": 7.92,
      "region": "eastus",
      "resource_group": "prod-rg"
    },
    ...
  ],
  "count": 5,
  "total_actual_cost": 1250.75,
  "total_savings": 524.25,
  "query_timestamp": "2026-07-21T14:53:00Z"
}
```

**Endpoint 2**: `GET /api/savings-plan-details/{year}/{month}`
- Same structure as reservation endpoint but filters for SavingsPlan pricing model

### 3. **Frontend UI (dashboard.html)**

**New Section**: "🎯 Where Discounts Were Applied"
- Location: Below Advisor Recommendations section
- Two action buttons:
  - 🏛️ **Reservation Details** - Shows where Reservations were applied
  - 📊 **Savings Plan Details** - Shows where Savings Plans were applied

**Resource Details Table** (7 columns):
| Column | Description |
|--------|-------------|
| Resource Name | Friendly name extracted from resource ID |
| Resource Type | Azure resource type (e.g., Microsoft.Compute/virtualMachines) |
| Region | Azure region where resource resides |
| Actual Cost | Amount actually paid after discounts |
| List Price | What would have been paid at on-demand rates |
| Savings | Discount value (List Price - Actual Cost) |
| Qty | Number of transactions/hours |

**Summary Cards**:
- 📊 Resources Count - How many resources used this discount type
- 💰 Total Actual Cost - Sum of all discounted costs
- 💸 Total Savings - Total discount value across all resources

### 4. **JavaScript Functions (dashboard.html)**

```javascript
// Fetch resource details from API
async function showDiscountDetails(type) {
  // type: "reservation" or "savingsplan"
  // Fetches /api/reservation-details/YYYY/MM or /api/savings-plan-details/YYYY/MM
  // Shows loading state during fetch
  // Calls renderDiscountDetails on success
}

// Render the data in the UI
function renderDiscountDetails(data, type) {
  // Populates resource table with API data
  // Updates summary cards with totals
  // Highlights active button (blue vs gray styling)
  // Shows empty state if no resources found
}
```

## How to Use

### For End Users:
1. **View Reservation Details**:
   - Click "🏛️ Reservation Details" button in "Where Discounts Were Applied" section
   - Dashboard fetches and displays all resources using Reservations for current month
   - See which VMs, databases, app services, etc. benefited from Reserved Instance pricing

2. **View Savings Plans Details**:
   - Click "📊 Savings Plan Details" button
   - See which compute resources used Savings Plans
   - Verify commitment effectiveness by comparing actual vs list price

3. **Analyze Savings**:
   - Sort mentally by highest savings column to identify best ROI resources
   - Check quantities to understand usage intensity
   - Review regions to identify geographic patterns

### For Developers:
- Endpoints are available for custom integrations
- Returns JSON suitable for exporting to BI tools or custom dashboards
- Uses existing Azure Cost Management API infrastructure (no new dependencies)

## Technical Details

### Data Flow:
1. User clicks button → `showDiscountDetails("reservation")` triggered
2. JavaScript fetches `/api/reservation-details/2026/7`
3. Backend `get_reservation_details()` queries Azure Cost Management API
4. `_extract_resource_details()` parses response:
   - Groups by resource ID and pricing model
   - Matches ActualCost with AmortizedCost (for list prices)
   - Calculates per-resource savings
   - Sorts by savings (descending)
5. JSON response returned to frontend
6. `renderDiscountDetails()` populates HTML table
7. User sees resource-level breakdown

### Performance:
- Response time: ~2-5 seconds (limited by Azure API rate limits)
- Caching: Uses same cache layer as dashboard (/api/dashboard)
- Rate limiting: Exponential backoff with 5 retries built-in
- No new API overhead beyond existing /api/dashboard queries

### Compatibility:
- ✅ Works with any Azure subscription with Cost Management API access
- ✅ Returns empty state gracefully if subscription has no Reservations/Savings Plans
- ✅ Handles authorization errors from Azure
- ✅ Responsive design (works on mobile via scrollable table)

## API Response Fields Explained

### Per-Resource Data:
- **resource_id**: Full Azure resource path (/subscriptions/...)
- **resource_name**: Extracted from last segment of resource ID
- **resource_type**: Azure resource provider type
- **pricing_model**: "Reservation" or "SavingsPlan"
- **actual_cost**: What was actually charged (after discount applied)
- **list_price**: What would be charged at on-demand rates (from AmortizedCost query)
- **savings**: Discount amount (list_price - actual_cost)
- **quantity**: Number of pricing periods (hours, days, etc.)
- **effective_price**: Per-unit cost (actual_cost / quantity)
- **region**: Azure region from resource metadata
- **resource_group**: Extracted from resource ID path

### Aggregates:
- **count**: Total resources in this pricing model
- **total_actual_cost**: Sum of all actual_cost values
- **total_savings**: Sum of all savings values
- **total_list_price**: Implied (can be calculated as total_actual_cost + total_savings)

## Testing Notes

**Tested Scenarios**:
✅ Subscriptions with Reservations → Shows resource details
✅ Subscriptions with Savings Plans → Shows resource details
✅ Subscriptions with neither → Shows empty state message
✅ Subscriptions without Cost Management access → Shows 401 error
✅ API rate limiting → Handles with exponential backoff
✅ HTML validation → All elements properly structured
✅ JavaScript functions → All functions present and accessible

**Endpoints Verified**:
- `/api/reservation-details/2026/7` → 200 OK with data
- `/api/savings-plan-details/2026/7` → 200 OK with data
- Both endpoints properly serialized to JSON
- Error messages properly formatted

## Future Enhancements

Potential improvements (not in this release):
- [ ] Add filtering by resource type (VM, App Service, SQL, etc.)
- [ ] Add search box to find specific resources by name
- [ ] Add export to CSV/Excel
- [ ] Add date range selector (not just current month)
- [ ] Add sorting by different columns (name, savings, cost, etc.)
- [ ] Add visualization (pie chart by resource type)
- [ ] Add historical comparison (savings this month vs last month)
- [ ] Add recommendation engine (e.g., "consider switching VM001 to reserved instance")

## Troubleshooting

**Issue**: Buttons not appearing in dashboard
- **Solution**: Refresh browser (Ctrl+F5) to get latest HTML/JavaScript

**Issue**: "Click button but nothing happens"
- **Solution**: Check browser console (F12) for JavaScript errors
- Ensure you're logged in (✓ Authenticated status visible)
- Ensure subscription is selected (not "Loading...")

**Issue**: "Click button and table shows no data"
- This is expected! Some subscriptions don't have Reservations or Savings Plans
- Check different subscriptions to find ones with active discounts
- See Advisor Recommendations for "Reservations" category for opportunities

**Issue**: "401 Unauthorized error"
- **Solution**: User doesn't have Cost Management API access on this subscription
- Try different subscription
- Contact Azure subscription admin to grant permissions

**Issue**: Slow response (>10 seconds)
- **Solution**: Azure Cost Management API is rate-limited
- Wait a moment and try again
- Don't click button repeatedly (builds up request backlog)

## Files Modified

1. **cost_api.py** (Added ~150 lines)
   - `ResourceSavingsDetail` dataclass
   - `_extract_resource_details()` method
   - `get_reservation_details()` method
   - `get_savings_plan_details()` method

2. **dashboard_api.py** (Added ~50 lines)
   - `GET /api/reservation-details/{year}/{month}` endpoint
   - `GET /api/savings-plan-details/{year}/{month}` endpoint

3. **dashboard.html** (Added ~100 lines)
   - HTML section: "Where Discounts Were Applied"
   - JavaScript: `showDiscountDetails(type)` function
   - JavaScript: `renderDiscountDetails(data, type)` function

## Summary

This feature enables users to drill down from aggregate savings numbers to see exactly which Azure resources benefited from Reservations and Savings Plans. The data is retrieved from the same Azure Cost Management API used by the main dashboard, maintaining consistency and reliability. The feature is fully functional and production-ready.

**Status**: ✅ **COMPLETE** - Ready for production use
