# Configure Get Azure Advisor for Live Tenant Data Only

This runbook defines the target Copilot Studio configuration for authenticated live Azure tenant analysis only. It contains no sample data, static knowledge, copied credentials, or reusable connection IDs.

## Source of truth

- Portable settings: [agent-recreation/get-azure-advisor-settings.yaml](agent-recreation/get-azure-advisor-settings.yaml)
- Agent instructions: [agent-recreation/agent-instructions.md](agent-recreation/agent-instructions.md)
- Original agent icon: [agent-recreation/get-azure-advisor-icon.png](agent-recreation/get-azure-advisor-icon.png)

The raw Copilot Studio export is not committed because generated IDs and connection references are environment-bound. The prior skill export and all uploaded files and websites are excluded because they are not live tenant data.

## Target configuration

| Area | Setting |
| --- | --- |
| Agent | Get Azure Advisor |
| Harness | GitHub Copilot harness (`cliagent-1.0.0`) |
| Model | Claude Sonnet 5 (`Sonnet5`) |
| Language | English (`1033`) |
| Live data tool | Azure Monitor Logs > Run query and list results V2 |
| Tool authentication | User |
| Static knowledge | None |
| Skills | None |
| Other tools | None |
| Web search | Off |
| Memory | Off |
| Connected agents | None |
| Workflows | None |
| Conversation starters | None |
| Authentication | Authenticate with Microsoft; always authenticate |
| Access policy | Group membership |
| Moderation | Medium |
| Public website channel | Not enabled |

## Configure it

1. Open the agent in the approved Power Platform environment.
2. Replace **Build > Instructions** with [the live-only instructions](agent-recreation/agent-instructions.md).
3. Keep only **Azure Monitor Logs > Run query and list results V2** under **Tools**.
4. Set tool authentication to **User**. Keep `Query` and `Time Range Type` filled by AI; `Timerange` remains dynamic. The output is `Value (object[])`.
5. Remove **Microsoft Learn Docs MCP Server** and any other tool that does not return authenticated live tenant data.
6. Remove every uploaded knowledge file and website knowledge source.
7. Remove every skill, including `azure-finops-cost-optimization`.
8. Leave web search and memory off. Do not add connected agents or workflows.
9. Keep **Authenticate with Microsoft**, group-membership access, user feedback, and Medium moderation.
10. Save and test with authorized identities before publishing to approved authenticated channels. Do not enable a public website channel.

## Required query context

Before running Azure Monitor Logs, the agent must ask the user to confirm:

- Tenant context
- Subscription or Log Analytics workspace scope
- Time range

The selected connection must use the signed-in user's delegated access. Do not replace User authentication with Maker authentication or a broader workload identity.

## Evidence rules

Every tenant finding must include:

- Source: Azure Monitor Logs
- Confirmed scope
- Collection time
- The returned rows that support the conclusion, summarized without exposing unnecessary customer data

Denied, unavailable, unsupported, and empty results are different states. An empty result is not evidence that spend, risk, waste, or recommendations are zero.

If live tenant evidence is unavailable, the agent must stop the assessment, state what access, scope, or data is missing, and avoid best-practice filler or inferred recommendations.

## Capability boundary

The agent can analyze only data available through Azure Monitor Logs for the authenticated user's permitted scope. It has no live Azure Advisor API, Cost Management API, Resource Graph, Power BI, Function App, custom API, or tenant-wide Azure MCP connection.

The name **Get Azure Advisor** does not establish an Azure Advisor integration. Do not claim that Azure Advisor recommendations, billing data, resource inventory, utilization, or savings estimates were queried unless a future approved live connector is explicitly added and validated.

## Validation

Before publishing, confirm all of the following with real authorized identities:

- Exactly one tool is configured: Azure Monitor Logs `QueryDataV2` using User authentication.
- Skills, uploaded files, websites, web search, memory, connected agents, and workflows are absent or off.
- The agent requests tenant, scope, and time range before querying.
- Findings identify Azure Monitor Logs, scope, and collection time.
- Insufficient permission, unavailable data, unsupported scope, and empty results remain distinct.
- The agent refuses to assess or recommend when live tenant evidence is unavailable.
- No credentials, connection IDs, tenant IDs, subscription IDs, generated findings, or sample costs are committed in this package.
