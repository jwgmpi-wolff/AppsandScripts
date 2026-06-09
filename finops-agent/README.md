# Azure FinOps Agent

This project provides an Azure Functions-based FinOps agent that:
- connects to the current Azure tenant via Azure CLI (`az login` / `az account set`)
- collects resource inventory and cost data
- calls Azure Advisor recommendations and associates them to the resources
- writes a SQLite database for Power BI consumption
- generates a PDF summary and a PowerPoint deck for download

## Functions

- `run_finops_report` (HTTP trigger): run an on-demand report.
- `scheduled_finops_report` (Timer trigger): runs daily at 06:00 UTC.

## Local run

1. `pip install -r requirements.txt`
2. `func start`
3. Call `http://localhost:7071/api/run_finops_report` to generate the artifacts.

The artifacts are written to the `artifacts/` folder and the SQLite database to `artifacts/finops_reports.db`.
