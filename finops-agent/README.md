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

## Build Prompts

### Prompt 1: Full Project Regeneration

```text
Build a complete Azure Functions Python project named finops-agent from scratch. Recreate all source files, configuration, and docs so it runs locally and generates FinOps artifacts.

Goal:
Create an Azure FinOps reporting function app that:
- Connects to the current Azure tenant/subscription via Azure CLI
- Collects resource inventory
- Collects consumption usage for the current reporting period
- Collects Azure Advisor recommendations
- Associates recommendations to resources
- Calculates commitment savings fields (Reservation and Savings Plan scenarios)
- Writes a SQLite report database
- Generates a PDF summary
- Generates a PowerPoint summary deck with a professional visual theme
- Exposes artifact download endpoints
- Returns tabular JSON and tabular HTML output for the report API

Tech stack and dependencies:
- Python 3.11+
- Azure Functions Python v2 programming model
- Dependencies:
	- azure-functions
	- reportlab
	- python-pptx
- Use only these dependencies unless absolutely required
- Keep code in one main file: function_app.py

Project files to create:
- function_app.py
- requirements.txt
- host.json
- local.settings.json (template with placeholders, no secrets)
- README.md
- .gitignore

Functional requirements:

1. Function app and auth behavior
- Initialize FunctionApp with function-level auth by default
- For local development, route auth should switch to anonymous when environment indicates Development
- Add three functions:
	- HTTP function run_finops_report at route run_finops_report, methods GET and POST
	- HTTP function finops_artifact at route finops_artifact, method GET
	- Scheduled function scheduled_finops_report, cron 0 0 6 * * * (daily 06:00 UTC)

2. Azure command execution
- Implement run_az helper that executes Azure CLI commands and parses JSON output
- Add fallback behavior when Azure CLI executable is unavailable:
	- Use ARM REST with managed identity token acquisition
	- Support account list, account set, resource list, advisor recommendation list, vm show (vm size), and consumption usage fallback handling
- Return useful runtime errors when neither CLI nor fallback can satisfy a command

3. Data collection model
- Compute reporting period using month boundaries
- Gather:
	- Subscriptions from account list
	- Resources with id, name, type, location, resourceGroup, subscriptionId
	- Consumption usage rows with resourceId, resourceName, cost, usageQuantity, date, meterCategory, resourceGroup, billingCurrency, currency, plus subscription info
	- Advisor recommendations with id, name, category, impact, severity, description, solution, resourceId, annualSavingsAmount, savingsAmount, extendedProperties
- Associate recommendations to resourceName and resourceType using resourceId lookup
- Normalize missing values safely

4. Savings estimation
- For every recommendation populate:
	- reservationSavings1Year
	- reservationSavings2Year
	- reservationSavings3Year
	- savingsPlanSavings1Year
	- savingsPlanSavings2Year
	- savingsPlanSavings3Year
	- savingsEstimateBasis
	- pricingCurrency
	- billingCurrency
	- currencyMatch
	- savingsCurrency
	- fxApplied
	- fxRateApplied
	- fxSource
	- estimatedAverageInstanceCount
	- observedUsageHoursInPeriod
- If resource is VM and pricing data is available:
	- Query Azure retail prices API for on-demand and reservation rates
	- Parse savingsPlan entries when available
	- Scale by observed usage profile from consumption data
	- Cache pricing results in SQLite table retail_price_cache with TTL
- If pricing-based estimate cannot be calculated:
	- Fall back to advisor annual savings fields when available
- Include optional FX conversion support from env-provided rates payload or URL

5. SQLite artifact
- Write database to artifacts folder as finops_reports.db
- Create and populate tables:
	- report_runs
	- resources
	- resource_costs
	- recommendations
- Ensure schema includes all recommendation savings and currency fields above
- Keep cache table retail_price_cache

6. PDF artifact
- Output file name: finops_summary.pdf
- Include:
	- report metadata (generated timestamp and period)
	- total cost in period
	- top resources table
	- commitment savings totals
	- advisor recommendations table
- Use compact readable formatting with reportlab

7. PowerPoint artifact
- Output file name: finops_summary.pptx
- Create 16:9 deck
- Professional styling requirements:
	- Consistent Segoe UI typography
	- Reduced body/table font sizes for dense readability
	- Corporate-looking color palette
	- Clean slide background
	- Styled table header with alternating row shading
- Slide requirements:
	- Slide 1: report title and period metadata, total recommendation/resource counts
	- Slide 2: advisor summary metrics (top categories, impact distribution, aggregate savings totals)
	- Slide 3: top advisor opportunities (sorted by impact then savings)
	- Slide 4: table of only resources associated to advisor recommendations
		- columns: Resource Name, Resource Type, Location, Resource Group, Recommendations, High Impact, Resv1Y Total, SP1Y Total
- Prevent table column index mismatch bugs by ensuring column count equals width definitions

8. API responses and report rendering
- run_finops_report should:
	- Collect snapshot
	- Generate SQLite, PDF, PPTX artifacts
	- Return JSON by default with tabular sections and pagination
	- Return HTML when format=html or Accept header prefers html
- JSON should include:
	- status
	- generatedAt
	- period
	- artifacts local paths
	- artifactUrls
	- tabs containing Resource Information and Advisor Recommendations
- HTML should include:
	- Artifact download links
	- Tabbed data tables
	- Horizontal scroll sync for wide tables
- Error handling:
	- Return HTTP 500 with message starting with: Report generation failed:
	- Log full exception details

9. Artifact download endpoint
- finops_artifact endpoint must serve:
	- name=database
	- name=pdf
	- name=powerpoint
- Return 400 for unknown names
- Return 404 if requested artifact not generated yet
- Return binary payload with appropriate MIME type and attachment filename

10. Scheduled run
- scheduled_finops_report executes full collection and artifact generation
- Log completion and artifact paths

11. Config and runtime
- Respect FINOPS_ARTIFACT_DIR env override, otherwise use temp dir finops-artifacts
- Ensure artifact directory exists
- Include HOST configuration via host.json using extension bundle 4.x
- local.settings.json must include:
	- FUNCTIONS_WORKER_RUNTIME set to python
	- AzureWebJobsStorage placeholder value only (no real credentials)

12. Documentation
- README must include:
	- Project purpose and capabilities
	- Functions list
	- Local run steps:
		- pip install -r requirements.txt
		- func start
		- invoke local run_finops_report endpoint
	- Note where artifacts are written
- Add clear section on required Azure login and subscription selection via az login and az account set

13. Quality requirements
- Strong type hints where practical
- Safe parsing for optional fields
- No hardcoded secrets
- Keep implementation production-safe and deterministic
- Ensure python -m py_compile function_app.py passes

Acceptance checks to run and report:
- python -m py_compile function_app.py
- Start function host locally
- Invoke run_finops_report endpoint once
- Confirm artifacts exist:
	- finops_reports.db
	- finops_summary.pdf
	- finops_summary.pptx
- Confirm finops_artifact endpoint downloads each artifact
- Confirm PPT generation completes without any column index out of range errors

Output format expected from you:
- Generate all files with full content
- Then provide a short runbook:
	- install
	- start
	- test endpoints
	- where artifacts are stored
```

### Prompt 2: Full Regeneration + Terraform Azure Deployment Scaffolding

```text
Build the complete finops-agent project from scratch and include Terraform-based Azure deployment scaffolding in the same generation pass.

Primary app requirements:
- Use all requirements from Prompt 1 (same functionality, endpoints, artifacts, and behavior).

Additional deployment scaffolding requirements:

1. Infrastructure as code (Terraform)
- Create an infra/terraform structure with:
	- providers.tf
	- versions.tf
	- variables.tf
	- main.tf
	- outputs.tf
	- terraform.tfvars.example
- Use azurerm provider and random provider where needed
- Target a production-ready baseline with minimal complexity

2. Azure resources to provision
- Resource Group
- Storage Account for Function App
- App Service Plan (Elastic Premium or Consumption based on variable)
- Linux Function App for Python
- Application Insights + Log Analytics Workspace
- Optional Key Vault (toggle by variable)

3. App configuration wiring
- Configure Function App application settings for:
	- FUNCTIONS_WORKER_RUNTIME=python
	- AzureWebJobsStorage from provisioned storage
	- FINOPS_ARTIFACT_DIR
	- optional FX settings placeholders
- Expose outputs for function app name, hostname, resource group, and app insights connection string

4. Packaging and deployment path
- Provide a simple deployment approach in README:
	- local zip package creation
	- az functionapp deployment source config-zip command example
- Keep CI/CD optional but scaffold-ready

5. Security and operations baseline
- Disable public access options only if they do not block basic demo use
- Add tags support via variables
- Add diagnostic settings stubs/comments where practical
- No hardcoded secrets in Terraform files

6. Repository structure updates
- Include .gitignore entries for Terraform state and local overrides
- Add README section:
	- prerequisites (Azure CLI, Terraform, Functions Core Tools)
	- terraform init/plan/apply steps
	- deploy app package steps
	- post-deploy smoke test steps for run_finops_report and finops_artifact

7. Acceptance criteria
- python -m py_compile function_app.py passes
- terraform validate passes
- terraform plan succeeds with terraform.tfvars.example adapted by user
- Function App is deployable with documented zip deployment command

Output requirements:
- Generate complete source for app + Terraform scaffolding
- Keep all generated code and docs cohesive
- Provide a final runbook with:
	1. local app run
	2. terraform deploy
	3. app package publish
	4. endpoint verification
```
