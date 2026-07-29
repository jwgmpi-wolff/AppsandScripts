# TokenPulse

[![Build Status](https://github.com/Jerrywolffms/jerrywolffms/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Jerrywolffms/jerrywolffms/actions/workflows/ci.yml)
[![Deploy Status](https://github.com/Jerrywolffms/jerrywolffms/actions/workflows/deploy-pages.yml/badge.svg?branch=main)](https://github.com/Jerrywolffms/jerrywolffms/actions/workflows/deploy-pages.yml)
![Node Version](https://img.shields.io/badge/node-22.x-339933)

TokenPulse is a React + TypeScript dashboard for analyzing token usage, spend, and optimization opportunities across projects and models.

It includes:

- A multi-page analytics UI (Dashboard, Usage Explorer, Projects, Models, Alerts, Recommendations)
- Simulated policy application for cost recommendations (per policy and apply-all)
- CI checks on pull requests and pushes
- Automated deployment options for GitHub Pages and Azure App Service

## Table of Contents

- Overview
- Tech Stack
- Features
- Screenshots
- Quick Start
- Available Scripts
- App Routes
- Project Structure
- Data Model
- Deployment and Redeploy from GitHub
- Azure App Service Deployment (Frontend + API Proxy)
- Troubleshooting
- Development Notes
- Future Improvements

## Overview

TokenPulse runs in live-data-only mode. It fetches tenant/resource usage from an API endpoint and does not ship a demo fallback.

All pages are blocked behind a live data gate until the API responds successfully.

Authentication is handled through Microsoft Entra interactive sign-in, with tenant/subscription-aware live ARM access.

## Tech Stack

- React 19
- TypeScript 6
- Vite 8
- Tailwind CSS 4
- React Router 7
- D3 for charts
- Lucide icons
- Oxlint

## Features

- Dashboard
  - 30-day token and cost KPIs
  - Daily token trend
  - Cost share by project
  - Daily provider cost distribution
  - Token taxonomy cards and breakdown tables (per-resource and per-model)
  - Capacity trend charts (TPM/RPM/PTU)
  - CSV/JSON export for taxonomy totals and daily history
- Usage Explorer
  - Filter by provider, project, date range
  - Sort by timestamp/tokens/cost
  - Paginated event-level table with totals
  - Sticky header + sticky first three columns for wide datasets
  - Wrapped long text fields with numeric columns prioritized to the left
- Authentication + Context
  - Microsoft Entra interactive browser login
  - Live subscription switching in-app
  - Global live time-window selector (15 minutes to Forever)
  - Always-visible window banner with exact since/until timestamps
- Projects
  - Cost and token breakdown by project
  - Project detail drill-down with model-level table
- Models
  - Model usage and pricing comparisons
  - Input/output price charts
- Alerts
  - Create, edit, enable/disable, and delete budget alerts
  - Global or project-scoped thresholds
- Recommendations
  - Cost-reduction suggestions generated from usage profile
  - Policy simulation controls per recommendation
  - Apply all / Reset simulation
  - Live projected before/after cost summary

## Screenshots

Add screenshots to the `docs/screenshots/` folder using the filenames below.

Auto-capture from local routes:

```bash
npm run screenshots:install
npm run dev
npm run screenshots:capture
```

Optional custom URL:

```bash
BASE_URL=http://localhost:5174 npm run screenshots:capture
```

### Dashboard

Main KPI and trend overview.

![Dashboard](docs/screenshots/dashboard.png)

### Usage Explorer

Event-level filtering and sorting table.

![Usage Explorer](docs/screenshots/usage-explorer.png)

### Projects

Project spend cards and drill-down detail view.

![Projects](docs/screenshots/projects.png)

### Models

Model pricing and usage comparison.

![Models](docs/screenshots/models.png)

### Alerts

Budget alert configuration and status cards.

![Alerts](docs/screenshots/alerts.png)

### Recommendations

Cost optimization recommendations with policy simulation.

![Recommendations](docs/screenshots/recommendations.png)

## Quick Start

### Prerequisites

- Node.js 20+ (Node.js 22 recommended)
- npm 10+

### Install

```bash
npm ci
```

### Run locally

```bash
npm run dev
```

### Run API (required for live auth/data)

```bash
npm run start:api
```

Then open `http://localhost:5173` and sign in from the Azure Authentication panel.

### Run locally with live API proxy

1. Copy `.env.example` to `.env` and set `TOKENPULSE_UPSTREAM_BASE_URL` to your tenant API.
2. Start frontend + API together:

```bash
npm run dev:full
```

Vite will print the local URL, typically:

- `http://localhost:5173`
- Or next available port (for example `http://localhost:5174`) if 5173 is occupied

## Live Window Options

TokenPulse can query live Azure metrics using selectable windows:

- Last 15 minutes
- Last 1 hour
- Last 6 hours
- Last 24 hours
- Last 7 days
- Last 30 days
- Last 90 days
- Last 1 year
- Forever

The active window and exact `since`/`until` timestamps are shown in the top authentication panel.

### Build

```bash
npm run build
```

### Preview production build

```bash
npm run preview
```

## Available Scripts

- `npm run dev`
  - Start Vite development server
- `npm run api:dev`
  - Start local API proxy server in watch mode on `http://127.0.0.1:8787`
- `npm run dev:full`
  - Run API proxy and Vite dev server concurrently
- `npm run start:api`
  - Start local API proxy server once (no watch)
- `npm run build`
  - Type-check (`tsc -b`) and create production bundle in `dist/`
- `npm run preview`
  - Serve production build locally
- `npm run lint`
  - Run Oxlint checks
- `npm run screenshots:install`
  - Install Playwright Chromium browser
- `npm run screenshots:capture`
  - Capture page screenshots into `docs/screenshots/`

## App Routes

The app uses `HashRouter` for static-host compatibility.

Routes:

- `/#/` Dashboard
- `/#/usage` Usage Explorer
- `/#/projects` Projects
- `/#/models` Models
- `/#/alerts` Alerts
- `/#/recommendations` Recommendations

Why hash routing is used:

- GitHub Pages does not provide SPA rewrite rules by default
- Hash routes prevent 404/route refresh issues on static hosting

## Project Structure

```text
tokenpulse/
  .github/
    workflows/
      ci.yml
      deploy-pages.yml
  server/
    index.mjs
  public/
  src/
    components/
      charts/
      ui/
      Layout.tsx
    data/
      liveApi.ts
      LiveDataContext.tsx
      queries.ts
      types.ts
    pages/
      AlertsPage.tsx
      DashboardPage.tsx
      ModelsPage.tsx
      ProjectsPage.tsx
      RecommendationsPage.tsx
      UsageExplorerPage.tsx
    App.tsx
    main.tsx
  vite.config.ts
  package.json
```

## Data Model

Core types are defined in `src/data/types.ts`:

- `Provider`
- `Model`
- `Project`
- `UsageEvent`
- `Alert`

Current data source:

- `VITE_TOKENPULSE_DATA_URL` (defaults to `/api/tokenpulse`)
- Expected response shape:

```json
{
  "providers": [{ "id": "azure-openai", "name": "Azure OpenAI", "color": "#0078d4" }],
  "models": [{ "id": "gpt-4o", "providerId": "azure-openai", "name": "GPT-4o", "inputPricePer1k": 0.005, "outputPricePer1k": 0.015 }],
  "projects": [{ "id": "proj-a", "name": "Project A", "color": "#6366f1", "description": "..." }],
  "usageEvents": [{ "id": "evt-1", "timestamp": "2026-06-24T10:15:00Z", "projectId": "proj-a", "modelId": "gpt-4o", "inputTokens": 1200, "outputTokens": 450, "cost": 0.012 }],
  "alerts": [{ "id": "alert-1", "name": "Monthly Budget", "scope": "global", "thresholdUsd": 500, "windowDays": 30, "enabled": true }]
}
```

- Alerts mutation endpoints used by the UI:
  - `POST ${VITE_TOKENPULSE_DATA_URL}/alerts`
  - `PUT ${VITE_TOKENPULSE_DATA_URL}/alerts/:id`
  - `DELETE ${VITE_TOKENPULSE_DATA_URL}/alerts/:id`

Proxy behavior in local development:

- Frontend requests `/api/tokenpulse` to the local proxy (`server/index.mjs`).
- Proxy forwards upstream to `${TOKENPULSE_UPSTREAM_BASE_URL}/tokenpulse`.
- Alert writes are forwarded to `${TOKENPULSE_UPSTREAM_BASE_URL}/tokenpulse/alerts...`.
- Proxy responses are explicitly `no-store`.

Query and formatting helpers:

- `src/data/queries.ts`

## Deployment and Redeploy from GitHub

### Included workflows

- `.github/workflows/ci.yml`
  - Trigger: pull requests and pushes to `main`
  - Steps: install, lint, build

- `.github/workflows/deploy-pages.yml`
  - Trigger: pushes to `main` and manual dispatch
  - Steps: install, build, upload Pages artifact, deploy

- `.github/workflows/deploy-azure-appservice.yml`
  - Trigger: manual dispatch only
  - Steps: install, build, provision/update App Service via Bicep, deploy frontend+proxy package
  - Guardrail: requires deployer to provide tenant/subscription and explicit `REAUTH_OK` confirmation

### One-time GitHub setup

1. Push this repository to GitHub.
2. Open repository Settings > Pages.
3. Set Source to GitHub Actions.
4. Ensure default branch is `main`.
5. Push a commit to `main` (or run deploy workflow manually).

### Redeploy flow

- Automatic: every push to `main`
- Manual: Actions > Deploy to GitHub Pages > Run workflow

### Build base path

Deployment workflow builds with:

```bash
npm run build -- --base=./
```

This ensures static assets resolve correctly when published by GitHub Pages.

## Azure App Service Deployment (Frontend + API Proxy)

TokenPulse can deploy frontend and API proxy together to one Linux App Service.

IaC template:

- `infra/appservice.bicep`

Deployment workflow:

- `.github/workflows/deploy-azure-appservice.yml`

Required repository variables:

- `AZURE_WEBAPP_NAME`
- `AZURE_APP_SERVICE_PLAN_NAME`
- `AZURE_RESOURCE_GROUP`
- `AZURE_LOCATION`

Required repository secrets:

- `AZURE_CLIENT_ID`
- `TOKENPULSE_UPSTREAM_BASE_URL`
- `TOKENPULSE_UPSTREAM_BEARER_TOKEN` (optional)

Required manual workflow inputs at deploy time:

- `tenant_id`
- `subscription_id`
- `reauth_confirmation` (must equal `REAUTH_OK`)

App behavior in Azure:

- Node 22 Linux runtime
- `npm start` launches `server/index.mjs`
- `server/index.mjs` serves both `dist/` and `/api/*`
- Health check endpoint: `/healthz`

## Troubleshooting

### Vite 403: outside of serving allow list (Windows)

Symptom:

- `403 Restricted` with `outside of Vite serving allow list`

Fix in this project:

- `vite.config.ts` has `server.fs.strict = false`
- Path allow list includes normalized variants

If it still appears:

1. Stop all running dev servers
2. Start again from project root: `npm run dev`
3. Open localhost URL printed by Vite (not a direct file path)

### Wrong app starts in terminal

If another project starts instead, run:

```powershell
cmd /c "cd /d c:\.git\tokenpulse && npm run dev"
```

### Port already in use

Vite will auto-select the next free port. Open the exact URL shown in terminal.

### GitHub Pages deploy does not run

Check:

1. Repo has Actions enabled
2. Pages Source is GitHub Actions
3. Push target is `main`
4. Workflow permissions include `pages: write` and `id-token: write`

## Development Notes

- Keep UI logic in page components under `src/pages/`
- Keep shared components in `src/components/ui/` and `src/components/charts/`
- Keep data querying/aggregation in `src/data/queries.ts`
- Prefer adding types in `src/data/types.ts` before implementing features

## Future Improvements

- Add authentication and per-team scopes
- Add persisted alert policies and recommendation state
- Add CSV export for usage and recommendation reports
- Add end-to-end tests for route flows and key dashboards
