const http = require('node:http');

const subscriptionId = process.env.AZURE_SUBSCRIPTION_ID;
const daysWindow = Number.parseInt(process.env.TOKENPULSE_ACTIVITY_WINDOW_DAYS || '30', 10);
const defaultAlertThreshold = Number.parseFloat(process.env.TOKENPULSE_DEFAULT_ALERT_THRESHOLD || '500');

let alerts = [
  {
    id: 'alert-default-monthly',
    name: 'Monthly Global Budget',
    scope: 'global',
    thresholdUsd: Number.isFinite(defaultAlertThreshold) ? defaultAlertThreshold : 500,
    windowDays: daysWindow,
    enabled: true,
  },
];

function json(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

async function readBody(req) {
  return await new Promise((resolve) => {
    let raw = '';
    req.on('data', chunk => (raw += chunk));
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch {
        resolve({});
      }
    });
  });
}

async function getArmToken(userToken) {
  // Prefer a delegated user token passed from the proxy (local dev / user-auth flow).
  if (userToken) return userToken;

  const resource = 'https://management.azure.com/';

  // App Service managed identity endpoint.
  if (process.env.IDENTITY_ENDPOINT && process.env.IDENTITY_HEADER) {
    const url = `${process.env.IDENTITY_ENDPOINT}?resource=${encodeURIComponent(resource)}&api-version=2019-08-01`;
    const response = await fetch(url, {
      headers: {
        'X-IDENTITY-HEADER': process.env.IDENTITY_HEADER,
      },
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(`Managed identity token request failed: ${response.status} ${text}`);
    }
    const tokenPayload = await response.json();
    return tokenPayload.access_token;
  }

  // IMDS fallback.
  const imds = await fetch(
    `http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=${encodeURIComponent(resource)}`,
    {
      headers: {
        Metadata: 'true',
      },
    },
  );
  if (!imds.ok) {
    const text = await imds.text();
    throw new Error(`IMDS token request failed: ${imds.status} ${text}`);
  }
  const tokenPayload = await imds.json();
  return tokenPayload.access_token;
}

async function armGet(url, token) {
  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`ARM GET failed ${response.status}: ${text}`);
  }
  return await response.json();
}

async function buildTokenPulseDataset(userToken) {
  if (!subscriptionId) {
    throw new Error('AZURE_SUBSCRIPTION_ID is not configured on this app.');
  }

  const token = await getArmToken(userToken);

  const groupsUrl = `https://management.azure.com/subscriptions/${subscriptionId}/resourcegroups?api-version=2021-04-01`;
  const resourcesUrl = `https://management.azure.com/subscriptions/${subscriptionId}/resources?api-version=2021-04-01`;

  const since = new Date();
  since.setDate(since.getDate() - daysWindow);
  const filter = encodeURIComponent(`eventTimestamp ge '${since.toISOString()}' and eventChannels eq 'Operation'`);
  const activityUrl = `https://management.azure.com/subscriptions/${subscriptionId}/providers/microsoft.insights/eventtypes/management/values?api-version=2015-04-01&$filter=${filter}`;

  const [groupResp, resourceResp, activityResp] = await Promise.all([
    armGet(groupsUrl, token),
    armGet(resourcesUrl, token),
    armGet(activityUrl, token),
  ]);

  const groups = Array.isArray(groupResp.value) ? groupResp.value : [];
  const resources = Array.isArray(resourceResp.value) ? resourceResp.value : [];
  const activities = Array.isArray(activityResp.value) ? activityResp.value : [];

  const projects = groups.map((g, idx) => ({
    id: String(g.name || `rg-${idx}`),
    name: String(g.name || `Resource Group ${idx + 1}`),
    color: ['#6366f1', '#ec4899', '#14b8a6', '#f59e0b', '#8b5cf6', '#10b981'][idx % 6],
    description: `Azure resource group in ${g.location || 'unknown region'}`,
  }));

  const providerMap = new Map();
  const modelMap = new Map();

  for (const r of resources) {
    const type = String(r.type || '').toLowerCase();
    const [providerNs] = type.split('/');
    if (!providerNs) continue;

    if (!providerMap.has(providerNs)) {
      providerMap.set(providerNs, {
        id: providerNs,
        name: providerNs,
        color: ['#0078d4', '#10a37f', '#d97706', '#4285f4', '#8b5cf6', '#f97316'][providerMap.size % 6],
      });
    }

    if (!modelMap.has(type)) {
      modelMap.set(type, {
        id: type.replace(/[^a-z0-9-]/gi, '-').toLowerCase(),
        providerId: providerNs,
        name: r.type,
        inputPricePer1k: 0,
        outputPricePer1k: 0,
      });
    }
  }

  const fallbackProjectId = projects[0]?.id || 'global';

  const usageEvents = activities.slice(0, 2000).map((a, idx) => {
    const resourceType = String(a.resourceType?.value || 'microsoft.resources/deployments').toLowerCase();
    const [providerNs] = resourceType.split('/');
    const operationName = String(
      a.operationName?.localizedValue
      || a.operationName?.value
      || 'Azure management operation',
    );
    const source = String(a.resourceType?.value || providerNs || 'Azure');
    const purpose = operationName;

    if (!providerMap.has(providerNs)) {
      providerMap.set(providerNs, {
        id: providerNs,
        name: providerNs,
        color: ['#0078d4', '#10a37f', '#d97706', '#4285f4', '#8b5cf6', '#f97316'][providerMap.size % 6],
      });
    }

    if (!modelMap.has(resourceType)) {
      modelMap.set(resourceType, {
        id: resourceType.replace(/[^a-z0-9-]/gi, '-').toLowerCase(),
        providerId: providerNs,
        name: a.resourceType?.value || resourceType,
        inputPricePer1k: 0,
        outputPricePer1k: 0,
      });
    }

    const projectId = String(a.resourceGroupName || fallbackProjectId);
    return {
      id: `evt-${a.eventDataId || idx}`,
      timestamp: a.eventTimestamp || new Date().toISOString(),
      projectId,
      modelId: modelMap.get(resourceType).id,
      inputTokens: 1,
      outputTokens: 0,
      cost: 0,
      source,
      purpose,
      resourceId: String(a.resourceId || ''),
      operationName,
    };
  });

  // Ensure every referenced project exists.
  const projectIds = new Set(projects.map(p => p.id));
  for (const evt of usageEvents) {
    if (!projectIds.has(evt.projectId)) {
      projects.push({
        id: evt.projectId,
        name: evt.projectId,
        color: '#94a3b8',
        description: 'Derived from Azure Activity Logs',
      });
      projectIds.add(evt.projectId);
    }
  }

  return {
    providers: Array.from(providerMap.values()),
    models: Array.from(modelMap.values()),
    projects,
    usageEvents,
    alerts,
  };
}

const server = http.createServer(async (req, res) => {
  try {
    const url = req.url || '/';

    if (req.method === 'GET' && url === '/healthz') {
      return json(res, 200, {
        ok: true,
        service: 'tokenpulse-tenant-api',
        subscriptionId: subscriptionId || null,
      });
    }

    if (req.method === 'GET' && url === '/api/tokenpulse') {
      const userToken = (req.headers.authorization || '').replace(/^Bearer\s+/i, '') || null;
      const dataset = await buildTokenPulseDataset(userToken || undefined);
      return json(res, 200, dataset);
    }

    if (req.method === 'POST' && url === '/api/tokenpulse/alerts') {
      const body = await readBody(req);
      const created = {
        id: `alert-${Date.now()}`,
        name: String(body.name || 'New Alert'),
        scope: body.scope === 'project' ? 'project' : 'global',
        projectId: body.projectId,
        thresholdUsd: Number.isFinite(Number(body.thresholdUsd)) ? Number(body.thresholdUsd) : 100,
        windowDays: Number.isFinite(Number(body.windowDays)) ? Number(body.windowDays) : 30,
        enabled: body.enabled !== false,
      };
      alerts = [...alerts, created];
      return json(res, 201, created);
    }

    const putMatch = req.method === 'PUT' ? url.match(/^\/api\/tokenpulse\/alerts\/([^/]+)$/) : null;
    if (putMatch) {
      const alertId = decodeURIComponent(putMatch[1]);
      const body = await readBody(req);
      let updated;
      alerts = alerts.map(a => {
        if (a.id !== alertId) return a;
        updated = {
          ...a,
          ...body,
          id: alertId,
        };
        return updated;
      });
      if (!updated) return json(res, 404, { error: 'Alert not found' });
      return json(res, 200, updated);
    }

    const delMatch = req.method === 'DELETE' ? url.match(/^\/api\/tokenpulse\/alerts\/([^/]+)$/) : null;
    if (delMatch) {
      const alertId = decodeURIComponent(delMatch[1]);
      alerts = alerts.filter(a => a.id !== alertId);
      return json(res, 200, { ok: true, id: alertId });
    }

    return json(res, 404, { error: 'Not found' });
  } catch (err) {
    return json(res, 500, {
      error: err instanceof Error ? err.message : 'Unexpected server error',
    });
  }
});

const port = Number.parseInt(process.env.PORT || '8080', 10);
server.listen(port, () => {
  console.log(`tokenpulse-tenant-api listening on ${port}`);
});
