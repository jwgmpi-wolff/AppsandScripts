# Project Structure & Quick Reference

## Directory Structure

```
cost_savings_reporting/
├── cost_api.py                 # Core Cost Management API client
├── dashboard_api.py            # FastAPI REST server
├── examples.py                 # Example usage script
├── requirements.txt            # Python dependencies
├── .env.example               # Environment template
├── .gitignore                 # Git ignore rules
├── Dockerfile                 # Docker image definition
├── docker-compose.yml         # Docker Compose setup
├── setup.bat                  # Windows setup script
├── setup.sh                   # Linux/macOS setup script
├── README.md                  # Full documentation
├── GETTING_STARTED.md         # Deployment guide
├── PROJECT_STRUCTURE.md       # This file
│
├── tests/
│   ├── __init__.py
│   └── test_cost_api.py       # Unit tests
│
└── docs/
    └── API_REFERENCE.md       # API endpoints reference
```

## Key Files

### `cost_api.py` - Cost Management Client
The core module that:
- Authenticates to Azure using `DefaultAzureCredential`
- Builds Cost Management API queries
- Calculates Reservation and Savings Plan savings
- Manages token lifecycle

**Key Classes:**
- `CostManagementClient` - Main client
- `MonthlySavings` - Data class for results

**Key Methods:**
- `get_current_month_savings()` - Current MTD
- `get_month_savings(year, month)` - Specific month
- `get_ytd_savings()` - Year-to-date
- `get_trailing_12_months()` - Last 12 months

### `dashboard_api.py` - FastAPI Server
REST API server with endpoints for:
- Health checks
- Current month reporting
- Historical month queries
- YTD summaries
- Dashboard data aggregation

**Base URL:** `http://localhost:8000`
**Docs:** `http://localhost:8000/docs` (Swagger UI)

### `examples.py` - Usage Examples
Demonstrates all client capabilities:
- Current month queries
- Specific month lookups
- YTD analysis
- Trailing 12-month reports
- Dashboard summaries

Run with:
```bash
export AZURE_SUBSCRIPTION_ID=your-sub-id
python examples.py
```

---

## API Quick Reference

### Health Check
```
GET /health
```
Returns: `{"status": "healthy", "client_initialized": true, "timestamp": "..."}`

### Current Month (MTD)
```
GET /api/current-month
```
Returns: Monthly savings for current month (month-to-date)

### Specific Month
```
GET /api/month/2026/06
```
Path Parameters:
- `year`: YYYY (e.g., 2026)
- `month`: MM (1-12)

Returns: Savings for that month

### Year-to-Date
```
GET /api/ytd
```
Returns: Current month + YTD summary + monthly breakdown

### Trailing 12 Months
```
GET /api/trailing-12-months
```
Returns: Last 12 months of savings data

### Full Dashboard
```
GET /api/dashboard
```
Returns: Comprehensive view with all metrics

---

## Data Schema

### Monthly Savings Response
```json
{
  "month": "2026-07",
  "reservation_savings": 15450.25,
  "savings_plan_savings": 8200.50,
  "total_savings": 23650.75,
  "reservation_quantity": 1247,
  "savings_plan_quantity": 892,
  "reservation_effective_price": 12.38,
  "savings_plan_effective_price": 9.20,
  "query_timestamp": "2026-07-21T15:30:00.000000"
}
```

### YTD Summary Response
```json
{
  "current_month": { ... },
  "ytd_summary": {
    "total_reservation_savings": 95000.00,
    "total_savings_plan_savings": 52000.00,
    "total_savings": 147000.00,
    "months_tracked": 7
  },
  "monthly_breakdown": {
    "2026-01": { ... },
    "2026-02": { ... },
    ...
  },
  "query_timestamp": "2026-07-21T15:30:00.000000"
}
```

---

## Authentication Flow

```
┌──────────────────────────────────────────────────────┐
│ 1. Application Start                                 │
└──────────────────┬───────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────┐
│ 2. DefaultAzureCredential Tries (in order):          │
│    - Environment variables                           │
│    - Azure CLI (az login)                            │
│    - Managed Identity (if on Azure)                  │
│    - Visual Studio authentication                    │
│    - Service Principal                               │
└──────────────────┬───────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────┐
│ 3. Get Access Token for:                             │
│    https://management.azure.com/.default             │
└──────────────────┬───────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────┐
│ 4. Add Token to Authorization Header:                │
│    "Authorization: Bearer <token>"                   │
└──────────────────┬───────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────┐
│ 5. Call Cost Management API                          │
│    POST /subscriptions/{subId}/providers/             │
│         Microsoft.CostManagement/query                │
└──────────────────┬───────────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────┐
│ 6. Parse Response & Calculate Savings                │
└──────────────────────────────────────────────────────┘
```

---

## Configuration Priority

Environment variables are resolved in this order:

1. `.env` file (if using `python-dotenv`)
2. System environment variables
3. Azure CLI credentials (`az login`)
4. Managed Identity (if running on Azure)
5. VS Code authentication (if in VS Code)

---

## Deployment Scenarios

| Scenario | Recommended | Setup Time |
|----------|-------------|-----------|
| Local Development | Azure CLI | 5 mins |
| Local Testing | Service Principal | 10 mins |
| Docker Container | Service Principal in env | 2 mins |
| Azure Container Apps | Managed Identity | 5 mins |
| Azure App Service | Managed Identity | 10 mins |
| Kubernetes | Service Principal/Workload ID | 15 mins |

---

## Performance Considerations

### Query Latency
- Initial query: 2-5 seconds
- Cached query: <100ms (with caching layer)
- Cost Management API timeout: 30 seconds

### Data Volume
- Current month: ~100KB
- YTD (6 months): ~500KB
- Trailing 12 months: ~1MB

### Rate Limits
- Cost Management API: ~100 requests/minute per subscription
- Recommendation: Cache aggressively for frequently-accessed data

---

## Common Operations

### Get savings for last 90 days
```python
from datetime import datetime, timedelta
from cost_api import CostManagementClient

client = CostManagementClient("sub-id")
end = datetime.now()
start = end - timedelta(days=90)

response = client.query_costs(start, end, ["Reservation", "SavingsPlan"])
```

### Export to CSV
```python
import csv
from cost_api import CostManagementClient

client = CostManagementClient("sub-id")
ytd = client.get_ytd_savings()

with open("savings.csv", "w") as f:
    writer = csv.writer(f)
    writer.writerow(["Month", "RI Savings", "SP Savings", "Total"])
    for month, savings in sorted(ytd.items()):
        writer.writerow([
            month,
            savings.reservation_savings,
            savings.savings_plan_savings,
            savings.total_savings,
        ])
```

### Alert on low utilization
```python
from cost_api import CostManagementClient

client = CostManagementClient("sub-id")
current = client.get_current_month_savings()

if current.reservation_savings < 1000:
    print("⚠️ Low RI savings this month!")
```

---

## Troubleshooting Checklist

- [ ] `AZURE_SUBSCRIPTION_ID` environment variable set?
- [ ] Authenticated to Azure (run `az account show`)?
- [ ] Identity has **Cost Management Reader** role?
- [ ] Subscription has Reservations or Savings Plans?
- [ ] Network access to `https://management.azure.com`?
- [ ] Python 3.10+ installed?
- [ ] All requirements installed (`pip install -r requirements.txt`)?
- [ ] Token not expired (app will refresh automatically)?

---

## Next Steps

1. **Run setup**: `./setup.sh` or `setup.bat`
2. **Test locally**: `python examples.py`
3. **Start API**: `uvicorn dashboard_api:app --reload`
4. **Deploy**: See [GETTING_STARTED.md](GETTING_STARTED.md)
5. **Monitor**: Set up Application Insights or equivalent

---

## Support Resources

- **API Docs**: http://localhost:8000/docs
- **README**: [README.md](README.md)
- **Getting Started**: [GETTING_STARTED.md](GETTING_STARTED.md)
- **Microsoft Docs**: [Cost Management API](https://learn.microsoft.com/en-us/rest/api/cost-management/)
- **Examples**: [examples.py](examples.py)
