# Build a Copilot Studio FinOps Agent with Live Azure Data

This runbook creates a Microsoft Copilot Studio agent that provides FinOps guidance and, after the user identifies and confirms an Azure scope, analyzes live Azure data through an authenticated tool.

The agent must never treat documentation, uploaded files, model knowledge, or sample values as tenant evidence. Live findings must come from the configured Azure data tool.

## 1. Decide the identity and access model

Choose the access model before creating the tool.

### Recommended: delegated user access

Use a Microsoft Entra ID authenticated custom connector with on-behalf-of (OBO) authentication. The connector and API operate as the signed-in user, so Azure RBAC controls which tenants, subscriptions, and resources the user can inspect.

Use this model when different users should see only the Azure data they are individually authorized to read.

### Alternative: managed identity at a fixed scope

The API can use its Function App managed identity. An Azure administrator grants that identity read-only roles at specific management group, subscription, or resource group scopes.

Use this model only when every authorized agent user is permitted to see all data available to that managed identity. The agent's user identity does not reduce the managed identity's Azure access.

Do not use a shared maker identity as a shortcut for broad tenant access.

## 2. Confirm prerequisites

Before building the agent, confirm:

- A Copilot Studio environment and license are available.
- The maker can create or use a Power Platform solution.
- Power Platform data loss prevention policies allow the required custom connector or agent flow.
- A Microsoft Entra administrator can create app registrations and grant consent when required.
- An Azure administrator can assign read-only RBAC at the approved scope.
- The live-data API is reachable over HTTPS from Power Platform.
- Production, test, and development use separate Power Platform environments and connections.

Do not paste client secrets, access tokens, function keys, or connection strings into agent instructions, topics, knowledge, or chat messages.

## 3. Define the live-data contract

Use one clearly named read-only tool, such as **Run FinOps analysis**. The tool should accept these inputs:

| Input | Required | Purpose |
| --- | --- | --- |
| `tenantId` | Yes | Microsoft Entra tenant selected by the user |
| `scopeType` | Yes | `managementGroup`, `subscription`, or `resourceGroup` |
| `scopeIds` | Yes | One or more identifiers within the selected tenant |
| `startDate` | Yes | Beginning of the requested analysis period |
| `endDate` | Yes | End of the requested analysis period |
| `includeCosts` | Yes | Explicitly requests cost collection |
| `includeAdvisor` | Yes | Explicitly requests Azure Advisor collection |
| `includeResources` | Yes | Explicitly requests resource inventory collection |

The API must validate that:

1. The caller is authenticated.
2. The token issuer, audience, signature, lifetime, and required scope or role are valid.
3. The requested subscription or resource group belongs to the requested tenant.
4. The effective Azure identity has access to every requested scope.
5. Collection remains read-only.

The tool response should include:

- Collection time in UTC.
- Requested tenant and Azure scopes.
- Effective identity mode: delegated user or managed identity.
- Reporting period and billing currency.
- Data sources queried.
- Findings with stable Azure resource or recommendation identifiers.
- Coverage by subscription or resource group.
- Separate `denied`, `unavailable`, `unsupported`, and `empty` results.
- Data freshness and known latency.
- Redacted, actionable errors.

Never convert a failed or denied query into an empty, healthy result.

## 4. Prepare the Azure data API

The API should collect only the data required for the requested analysis. Typical read-only sources are:

- Azure Cost Management Query or Cost Details APIs for actual and amortized cost.
- Azure Resource Graph for inventory, tags, locations, SKUs, and resource state.
- Azure Advisor for cost recommendations.
- Azure Consumption budgets for budget status.
- Azure Monitor metrics only when utilization evidence is required and the approved identity has access.

The API should return normalized, bounded JSON rather than raw unbounded Azure responses. Include pagination or aggregation and keep the typical response within the Copilot Studio tool timeout and payload limits.

This repository's `run_finops_report` endpoint already accepts `tenantId` and `subscriptionId`, but it is not yet a production OBO boundary. It currently accepts a bearer token without validating the token's audience, issuer, signature, or application scope. Do not connect Copilot Studio to that bearer-token path for production until API-side validation and OBO are implemented.

For the managed identity model, grant the deployed Function App identity only the approved read roles and protect the HTTP endpoint with Microsoft Entra authentication or another approved API gateway. A function key alone identifies a caller that has the key; it does not provide user-level authorization.

## 5. Assign least-privilege Azure roles

Assign roles at the narrowest approved management group, subscription, or resource group scope.

For a read-only FinOps assessment, start with:

- **Reader** for resource inventory.
- **Cost Management Reader** for cost data, cost configuration, budgets, exports, and cost recommendations.
- **Monitoring Reader** only when the analysis uses Azure Monitor metrics or logs.

Do not assign **Owner**, **Contributor**, or **Cost Management Contributor** for a read-only agent. Recommendation execution is a separate workflow that requires explicit user approval and service-specific write roles.

After role assignment, test access with the same identity mode the production tool will use. Record the tenant, scope, identity object, role, approver, and review date outside the agent instructions.

## 6. Configure delegated OBO authentication

Skip this section only when using the fixed-scope managed identity model.

Follow Microsoft's OBO custom connector procedure:

1. Register the FinOps API in Microsoft Entra ID.
2. Under **Expose an API**, create a delegated scope such as `FinOps.Read`.
3. Configure the FinOps API to validate tokens issued for its application ID URI and the required delegated scope.
4. Create a separate single-tenant app registration for the custom connector.
5. Add the FinOps API's delegated `FinOps.Read` permission to the connector app.
6. Grant tenant admin consent if organizational policy requires it.
7. Expose an `access_as_user` scope on the connector app and authorize the Azure API Connections service principal as documented by Microsoft.
8. In the custom connector security settings, select **OAuth 2.0** and **Microsoft Entra ID**.
9. Set **Enable on-behalf-of login** to `true` and request only the FinOps API delegated scope.
10. Add the custom connector redirect URL to the connector app registration.
11. Share the connector with pilot users using the minimum required permission.

Prefer federated credentials or certificates where the supported connector path permits them. If a client secret is required, store it only in the connector configuration, set an owner and expiration alert, and rotate it according to organizational policy.

## 7. Create the Copilot Studio agent

1. Open [Microsoft Copilot Studio](https://copilotstudio.microsoft.com/).
2. Select the correct Power Platform environment.
3. Select **Agents** and then **Create blank agent** or **New agent**, depending on the current experience.
4. Name the agent **FinOps CCoE Advisor**.
5. Add this description:

   > Provides evidence-backed Azure FinOps analysis for a user-confirmed tenant and scope, plus grounded Microsoft guidance. It performs read-only assessment and does not make Azure changes.

6. Save the agent in an organizational solution so it can move through development, test, and production environments.

Copilot Studio labels can change between experiences. Use the current **Overview**, **Build**, **Tools**, **Knowledge**, **Topics**, **Settings**, and **Publish** pages rather than relying on a screenshot.

## 8. Add the agent instructions

On the agent **Overview** page, edit **Instructions** and paste the following text. After the live-data tool is added, replace `[Run FinOps analysis tool]` with an actual slash reference to that tool.

```text
You are the FinOps CCoE Advisor for Microsoft Azure.

PURPOSE
Help cloud engineering, platform, finance, FinOps, and leadership teams understand Azure cost, governance, accountability, and optimization opportunities. Perform assessment only. Never change Azure resources, budgets, policies, role assignments, reservations, or savings plans.

EVIDENCE RULES
- Treat [Run FinOps analysis tool] output as the only source of facts about the user's Azure environment.
- Use configured Microsoft knowledge sources for guidance, definitions, and implementation practices, never as evidence of tenant state.
- Never invent tenant IDs, subscription IDs, resources, costs, utilization, budgets, tags, recommendations, prices, discounts, commitments, savings, or access results.
- Never substitute examples, prior conversations, or general knowledge for missing live data.
- Clearly label guidance that is not based on live tenant evidence.
- Never ask a user to provide a password, client secret, access token, function key, or connection string in chat.

REQUIRED CONTEXT AND CONSENT
Before any live Azure analysis, obtain all of the following:
1. Microsoft Entra tenant ID or an unambiguous tenant name that the authenticated connection can resolve.
2. Scope type: management group, subscription, or resource group.
3. Exact scope ID or IDs.
4. Analysis start and end dates.
5. Confirmation that the user wants a read-only analysis of those scopes.

Summarize the requested tenant, scopes, period, and data categories, then ask the user to confirm. Do not call the tool until the user confirms. Do not retain or reuse a scope from another conversation without confirmation.

LIVE DATA COLLECTION
- Require the user to sign in or authorize the connection when prompted.
- Call [Run FinOps analysis tool] only after context and confirmation are complete.
- Use the authenticated identity's current Azure RBAC. Do not claim access beyond the tool result.
- If a scope is denied, unavailable, unsupported, stale, or empty, report that exact state and the affected scope.
- If collection is partial, analyze only returned evidence and state what is missing.
- Do not estimate savings unless the tool returns a documented estimate, currency, period, and calculation basis.
- Do not infer idleness from cost alone. Require appropriate utilization evidence before calling a resource idle.
- Do not recommend a reservation or savings plan purchase without eligible usage history, term, scope, payment option, utilization assumptions, currency, and break-even or risk information.

ANALYSIS AREAS
- Spend trend, major cost drivers, budget status, and forecast where returned by the tool.
- Resource ownership, allocation, tagging, and governance coverage.
- Azure Advisor cost recommendations and their evidence identifiers.
- Waste indicators supported by live cost, state, and utilization evidence.
- Commitment opportunities supported by eligible usage evidence.
- FinOps maturity in visibility, optimization, governance, accountability, and reporting.

RESPONSE FORMAT
Start live-analysis responses with:
- Scope analyzed
- Reporting period
- Collected at (UTC)
- Identity mode
- Data sources
- Coverage and limitations

For each finding provide:
- Finding
- Evidence
- Business impact
- Recommended action
- Potential benefit, only when supported by evidence
- Priority: High, Medium, or Low

End with a prioritized read-only action plan. Distinguish observed facts from recommendations. Use concise business language and tables when they improve clarity.
```

## 9. Add Microsoft guidance as knowledge

Knowledge improves guidance but does not provide live tenant data.

1. Go to **Knowledge** and select **Add knowledge**.
2. Select **Public websites**.
3. Add each approved Microsoft Learn root separately:
   - `https://learn.microsoft.com/azure/cost-management-billing/`
   - `https://learn.microsoft.com/azure/advisor/`
   - `https://learn.microsoft.com/azure/cloud-adoption-framework/`
   - `https://learn.microsoft.com/azure/well-architected/cost-optimization/`
4. Give every source a specific name and description so the orchestrator knows when to use it.
5. Turn off **Web Search** if the agent must not search the broader web.
6. Turn off **Allow the AI to use its own general knowledge** when policy requires answers to stay within configured sources and tools.
7. Test citations and verify that retrieved pages are from the intended Microsoft domains.

If the current environment supports **Microsoft Learn Docs MCP Server**:

1. Go to **Tools** and select **Add a tool**.
2. Search for **Microsoft Learn Docs MCP**.
3. Select the certified connector, then select **Add and configure**.
4. Add an instruction telling the agent to use it for Microsoft product documentation.
5. Test the MCP tool and inspect the activity trace.

The Learn MCP tool is optional and overlaps with Microsoft Learn website knowledge. It does not grant Azure tenant access.

## 10. Add internal guidance safely

Add only real, approved content. Do not create placeholder documents or upload files merely because a filename appears in an example.

Preferred approach:

1. Store controlled FinOps, tagging, governance, and operating-model documents in an approved SharePoint location.
2. Add that SharePoint location as a knowledge source.
3. Use end-user Microsoft Entra authentication so users receive only content they can access.
4. Add source owners, review dates, and document status to the content.
5. Remove obsolete or draft content from production knowledge.

Adding a Teams channel publishes the agent to Teams; it does not by itself make Teams messages a knowledge source. Add Microsoft 365 data only through a supported, approved connector or MCP server with the required permissions and retention controls.

## 11. Add the live Azure tool

### Custom connector path

1. Go to **Tools** and select **Add a tool**.
2. Select **New tool** > **Custom connector**.
3. Import the reviewed OpenAPI definition for the FinOps API.
4. Configure Microsoft Entra OAuth and OBO as described earlier.
5. Expose only read operations needed by the agent.
6. Use clear operation and parameter descriptions.
7. Set the tool to use end-user credentials.
8. Add and configure the tool in the agent.
9. For each required scope input, set **Ask the user** or pass the value from the explicit scope-confirmation topic. Do not let the model invent IDs.
10. Replace the placeholder in the agent instructions with a slash reference to this tool.

### Agent flow path

Use an agent flow only when it adds required orchestration or connector support.

1. Create a solution flow with **When an agent calls the flow** as the trigger.
2. Add the same tenant, scope, date, and data-category inputs defined in the live-data contract.
3. Call the protected FinOps API through the authenticated connector.
4. Add **Respond to the agent** with a bounded, structured result.
5. Keep the response synchronous and within the documented action limit, or use a supported asynchronous design for the target channel.
6. Publish the flow.
7. In the agent, select **Tools** > **Add a tool** > **Flow**, then add and configure it.

Do not expose a generic HTTP action that accepts arbitrary URLs, arbitrary Azure Resource Manager paths, or arbitrary query text from the model.

## 12. Build explicit scope confirmation

Create a topic named **Confirm Azure analysis scope** when deterministic collection is required.

The topic should:

1. Require authentication.
2. Ask for the tenant ID or resolve a tenant selection through the authenticated tool.
3. Ask for scope type and exact scope IDs.
4. Ask for start and end dates.
5. Ask which data categories to collect.
6. Display a summary without exposing secrets or tokens.
7. Require an explicit **Confirm** or **Cancel** response.
8. Call the live-data tool only after **Confirm**.
9. Clear the collected scope variables after completion or cancellation when they are no longer needed.

For cross-tenant analysis, the user must authenticate in the requested tenant and have Azure RBAC on the requested scope. A tenant ID entered in chat does not grant access.

## 13. Configure agent authentication and sharing

1. Go to **Settings** > **Security** > **Authentication**.
2. Require users to sign in.
3. For Teams and Microsoft 365 scenarios that do not require `User.AccessToken`, use **Authenticate with Microsoft** where supported.
4. If the agent topic itself needs `User.AccessToken`, use **Authenticate manually** with Microsoft Entra ID and prefer federated credentials.
5. Publish after changing authentication; authentication changes do not take effect until publication.
6. Share the agent only with the pilot security group.
7. Share the custom connector and connection permissions separately as required.

Agent authentication, connector authentication, Azure RBAC, and agent sharing are separate controls. Validate all four.

## 14. Test before publishing

Run tests with a dedicated nonproduction subscription containing known, real resources. Do not use fabricated responses in the production tool path.

Test these cases:

| Test | Expected result |
| --- | --- |
| User asks for tenant analysis without a tenant or scope | Agent requests the missing context and does not call the tool |
| User supplies scope but has not confirmed | Agent summarizes scope and asks for confirmation |
| User confirms an authorized scope | Tool runs and response includes collection time, scope, sources, and coverage |
| User requests an unauthorized subscription | Agent reports denied access; it does not return other subscriptions |
| User enters a subscription from another tenant | Tool rejects the tenant/scope mismatch |
| Cost API is unavailable but inventory succeeds | Agent reports partial coverage and analyzes only inventory evidence |
| No resources match a valid query | Agent reports an evidence-backed empty result, not a collection failure |
| User asks for exact savings without evidence | Agent declines to invent a number and explains the required data |
| User asks the agent to delete or resize a resource | Agent refuses to perform the change and provides a review plan |
| User pastes a token or secret | Agent does not repeat or use it and directs the user to the approved sign-in flow |

Use the Copilot Studio activity trace to verify the selected tool, arguments, outputs, and citations. Review Power Platform connection references and the API's redacted audit logs.

## 15. Publish and operate

1. Complete security, privacy, DLP, and data-owner review.
2. Publish the agent.
3. Add only the approved channel, such as Teams and Microsoft 365 Copilot.
4. Pilot with a security group before wider release.
5. Monitor tool failures, authorization denials, throttling, latency, and incomplete collection.
6. Review Azure role assignments, connector ownership, app consent, certificates or secrets, knowledge sources, and tool schemas on a defined schedule.
7. Republish after changes to instructions, tools, knowledge, or authentication.

## 16. User prompt examples

These prompts intentionally require the agent to obtain missing context before analysis:

- Analyze last month's Azure cost drivers for my approved subscription.
- Assess tag coverage for the subscriptions I select.
- Show Azure Advisor cost recommendations for a confirmed scope.
- Compare actual cost with budget for the requested reporting period.
- Create a prioritized FinOps backlog from the live evidence available to me.
- Assess our FinOps maturity, separating tenant evidence from policy guidance.

## Official references

- [Create and configure a Copilot Studio agent](https://learn.microsoft.com/microsoft-copilot-studio/fundamentals-get-started)
- [Write agent instructions](https://learn.microsoft.com/microsoft-copilot-studio/authoring-instructions)
- [Use Power Platform connectors as tools](https://learn.microsoft.com/microsoft-copilot-studio/advanced-connectors)
- [Configure OBO authentication for custom connectors](https://learn.microsoft.com/microsoft-copilot-studio/advanced-custom-connector-on-behalf-of)
- [Configure user authentication in Copilot Studio](https://learn.microsoft.com/microsoft-copilot-studio/configuration-end-user-authentication)
- [Add a public website as a knowledge source](https://learn.microsoft.com/microsoft-copilot-studio/knowledge-add-public-website)
- [Add an agent flow as a tool](https://learn.microsoft.com/microsoft-copilot-studio/flow-agent)
- [Add an MCP server as a tool](https://learn.microsoft.com/microsoft-copilot-studio/mcp-add-components-to-agent)
- [Use Microsoft Learn MCP Server in Copilot Studio](https://learn.microsoft.com/training/support/mcp-get-started-copilot-studio)
- [Understand Cost Management scopes and RBAC](https://learn.microsoft.com/azure/cost-management-billing/costs/understand-work-scopes)
- [Azure Cost Management REST API](https://learn.microsoft.com/rest/api/cost-management/)
- [Azure Resource Graph REST API](https://learn.microsoft.com/rest/api/azureresourcegraph/)
- [Azure Advisor REST API](https://learn.microsoft.com/rest/api/advisor/)

Last verified against Microsoft documentation: 2026-08-05.