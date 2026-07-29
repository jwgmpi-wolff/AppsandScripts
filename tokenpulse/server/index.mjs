import express from 'express';
import cors from 'cors';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
import { InteractiveBrowserCredential } from '@azure/identity';

try {
  const envPath = new URL('../.env', import.meta.url);
  const lines = readFileSync(envPath, 'utf8').split('\n');
  for (const line of lines) {
    const m = line.match(/^\s*([^#][^=]*?)\s*=\s*(.*)\s*$/);
    if (m && !(m[1] in process.env)) process.env[m[1]] = m[2];
  }
} catch {
  // Optional local .env file.
}

const app = express();
const port = Number.parseInt(process.env.PORT ?? '8787', 10);
const upstreamBaseUrl = process.env.TOKENPULSE_UPSTREAM_BASE_URL;
const upstreamBearerToken = process.env.TOKENPULSE_UPSTREAM_BEARER_TOKEN;
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const distDir = path.resolve(__dirname, '..', 'dist');
const indexHtmlPath = path.join(distDir, 'index.html');
const FETCH_TIMEOUT_MS = Number.parseInt(process.env.TOKENPULSE_FETCH_TIMEOUT_MS ?? '25000', 10);
const ARM_SCOPE = 'https://management.azure.com/.default';
const COLORS = ['#6366f1', '#ec4899', '#14b8a6', '#f59e0b', '#8b5cf6', '#10b981'];

let serverCredential = null;
let currentSubscriptionId = process.env.AZURE_SUBSCRIPTION_ID ?? null;
let cachedServerToken = null;
let cachedServerTokenExpiresAt = 0;
let localAlerts = null;

app.use(cors());
app.use(express.json({ limit: '1mb' }));
app.use(express.static(distDir, {
  maxAge: 0,
  etag: false,
  lastModified: false,
  setHeaders: (res) => {
    res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');
  },
}));

function noStore(res) {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Expires', '0');
}

function buildHeaders(incomingHeaders = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...incomingHeaders,
  };

  if (upstreamBearerToken) {
    headers.Authorization = `Bearer ${upstreamBearerToken}`;
  }

  return headers;
}

function getAuthHeaderToken(req) {
  const auth = String(req.headers.authorization ?? '').trim();
  const m = auth.match(/^Bearer\s+(.+)$/i);
  return m?.[1]?.trim() ?? null;
}

function decodeJwtPayload(token) {
  try {
    const parts = token.split('.');
    if (parts.length < 2) return null;
    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = payload + '='.repeat((4 - (payload.length % 4 || 4)) % 4);
    return JSON.parse(Buffer.from(padded, 'base64').toString('utf8'));
  } catch {
    return null;
  }
}

async function getServerArmAccessToken(forceRefresh = false) {
  if (!serverCredential) return null;

  const now = Date.now();
  if (!forceRefresh && cachedServerToken && cachedServerTokenExpiresAt > now + 60_000) {
    return cachedServerToken;
  }

  const token = await serverCredential.getToken(ARM_SCOPE);
  if (!token?.token) return null;

  cachedServerToken = token.token;
  cachedServerTokenExpiresAt = token.expiresOnTimestamp ?? (now + 50 * 60_000);
  return cachedServerToken;
}

async function armGet(url, token) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  let resp;
  try {
    resp = await fetch(url, {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      signal: controller.signal,
    });
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      throw new Error(`Request timed out after ${FETCH_TIMEOUT_MS}ms while calling Azure ARM.`);
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }

  if (!resp.ok) {
    const t = await resp.text();
    throw new Error(`ARM ${resp.status}: ${t.slice(0, 400)}`);
  }

  return resp.json();
}

async function listArmSubscriptions(token) {
  const response = await armGet('https://management.azure.com/subscriptions?api-version=2022-12-01', token);
  const items = Array.isArray(response.value) ? response.value : [];
  return items
    .filter(item => item && typeof item === 'object' && String(item.state ?? '').toLowerCase() === 'enabled')
    .map(item => ({
      id: String(item.subscriptionId ?? ''),
      name: String(item.displayName ?? item.subscriptionId ?? 'Unknown subscription'),
      tenantId: String(item.tenantId ?? ''),
      isDefault: false,
    }))
    .filter(item => item.id);
}

function buildTenantsFromSubscriptions(subscriptions) {
  const tenantMap = new Map();
  for (const sub of subscriptions ?? []) {
    if (!sub || typeof sub !== 'object') continue;
    const tenantId = String(sub.tenantId ?? '');
    if (!tenantId) continue;
    if (!tenantMap.has(tenantId)) {
      tenantMap.set(tenantId, {
        id: tenantId,
        name: tenantId,
        defaultDomain: '',
      });
    }
  }
  return Array.from(tenantMap.values());
}

async function buildServerAuthResponse() {
  if (!serverCredential) {
    return {
      authenticated: false,
      account: null,
      tenants: [],
      subscriptions: [],
      selectedTenantId: null,
      selectedSubscriptionId: null,
    };
  }

  const token = await getServerArmAccessToken();
  if (!token) {
    return {
      authenticated: false,
      account: null,
      tenants: [],
      subscriptions: [],
      selectedTenantId: null,
      selectedSubscriptionId: null,
    };
  }

  const claims = decodeJwtPayload(token) ?? {};
  const subscriptions = await listArmSubscriptions(token);
  const tenants = buildTenantsFromSubscriptions(subscriptions);

  if (!currentSubscriptionId || !subscriptions.some(sub => sub?.id === currentSubscriptionId)) {
    currentSubscriptionId = subscriptions[0]?.id ?? null;
  }

  const selectedSub = subscriptions.find(sub => sub?.id === currentSubscriptionId) ?? null;
  const selectedTenantId = selectedSub?.tenantId ?? (String(claims.tid ?? tenants[0]?.id ?? '') || null);

  return {
    authenticated: true,
    account: {
      name: String(claims.name ?? claims.preferred_username ?? claims.upn ?? 'Signed-in user'),
      id: selectedSub?.id ?? currentSubscriptionId,
      tenantId: selectedTenantId,
      user: { name: String(claims.preferred_username ?? claims.upn ?? claims.email ?? '') },
    },
    tenants,
    subscriptions,
    selectedTenantId,
    selectedSubscriptionId: selectedSub?.id ?? currentSubscriptionId,
  };
}

function parseResourceGroup(resourceId) {
  return String(resourceId ?? '').split('/resourceGroups/')[1]?.split('/')[0] ?? 'unknown';
}

function classifyTokenMetric(metricName) {
  const lower = String(metricName ?? '').toLowerCase();
  if (lower.includes('output') || lower.includes('completion')) return 'output';
  if (lower.includes('input') || lower.includes('prompt')) return 'input';
  return 'total';
}

const METRIC_CATEGORY_DEFS = [
  { key: 'inputTokens', label: 'Input Tokens', unit: 'tokens', match: /(inputtoken|input_token|prompttoken|processedprompttoken)/i, usageBucket: 'input' },
  { key: 'outputTokens', label: 'Output Tokens', unit: 'tokens', match: /(outputtoken|output_token|completiontoken|generatedtoken)/i, usageBucket: 'output' },
  { key: 'totalTokens', label: 'Total Tokens', unit: 'tokens', match: /(totaltoken|tokenconsumption|totaltokensconsumed|tokens$)/i, usageBucket: 'total' },
  { key: 'cachedInputTokens', label: 'Cached Input Tokens', unit: 'tokens', match: /(cached.*inputtoken|cachereadinputtoken|ephemeral\d+[mh]inputtoken|cachematch)/i, usageBucket: 'input' },
  { key: 'cachedTokens', label: 'Cached Tokens', unit: 'tokens', match: /(cachedtoken|cachetoken)/i, usageBucket: 'total' },
  { key: 'audioInputTokens', label: 'Audio Input Tokens', unit: 'tokens', match: /(audioinputtoken|voiceliveaudioinputtoken|audioprompttoken)/i, usageBucket: 'input' },
  { key: 'audioOutputTokens', label: 'Audio Output Tokens', unit: 'tokens', match: /(audiooutputtoken|voiceliveaudiooutputtoken|audiocompletiontoken)/i, usageBucket: 'output' },
  { key: 'embeddingTokens', label: 'Embedding Tokens', unit: 'tokens', match: /(embeddingtoken|embedtoken)/i, usageBucket: 'input' },
  { key: 'trainingTokens', label: 'Training Tokens', unit: 'tokens', match: /(trainingtoken|finetune.*train)/i, usageBucket: 'input' },
  { key: 'validationTokens', label: 'Validation Tokens', unit: 'tokens', match: /(validationtoken|finetune.*valid)/i, usageBucket: 'input' },
  { key: 'cacheReadTokens', label: 'Cache Read Tokens', unit: 'tokens', match: /(cachereadtoken|cache.*read.*token)/i, usageBucket: 'input' },
  { key: 'cacheWriteTokens', label: 'Cache Write Tokens', unit: 'tokens', match: /(cachewritetoken|cache.*write.*token)/i, usageBucket: 'input' },
  { key: 'reasoningTokens', label: 'Reasoning Tokens', unit: 'tokens', match: /(reasoningtoken)/i, usageBucket: 'input' },
  { key: 'textTokens', label: 'Text Tokens', unit: 'tokens', match: /(texttoken|voicelivetextinputtoken|voicelivetextoutputtoken)/i, usageBucket: 'total' },
  { key: 'imageTokens', label: 'Image Tokens', unit: 'tokens', match: /(imagetoken|visiontoken)/i, usageBucket: 'total' },
  { key: 'tpm', label: 'Tokens Per Minute (TPM)', unit: 'perMinute', match: /(tokensperminute|\btpm\b|azureopenaitokenpersecond|tokenspersecond)/i, usageBucket: 'capacity' },
  { key: 'rpm', label: 'Requests Per Minute (RPM)', unit: 'perMinute', match: /(requestsperminute|\brpm\b|totaltokencalls|tokentransaction)/i, usageBucket: 'capacity' },
  { key: 'ptuUtilization', label: 'PTU Utilization', unit: 'percent', match: /(ptu|provisionedthroughput|throughputunit)/i, usageBucket: 'capacity' },
];

function getMetricCategory(metricName) {
  const name = String(metricName ?? '');
  for (const def of METRIC_CATEGORY_DEFS) {
    if (def.match.test(name)) return def;
  }
  return {
    key: 'otherTokenMetrics',
    label: 'Other Token Metrics',
    unit: 'tokens',
    usageBucket: 'total',
  };
}

function incrementCategoryMap(targetMap, category, value) {
  if (!targetMap.has(category.key)) {
    targetMap.set(category.key, {
      key: category.key,
      label: category.label,
      unit: category.unit,
      total: 0,
    });
  }
  targetMap.get(category.key).total += value;
}

function serializeCategoryMap(targetMap) {
  return Array.from(targetMap.values()).sort((a, b) => b.total - a.total);
}

function getModelPricing(modelName) {
  const AZURE_OPENAI_MODELS = {
    'gpt-5-mini': { inputPricePer1k: 0.00055, outputPricePer1k: 0.0022 },
    'gpt-5': { inputPricePer1k: 0.0075, outputPricePer1k: 0.03 },
    'gpt-4o': { inputPricePer1k: 0.0025, outputPricePer1k: 0.01 },
    'gpt-4o-mini': { inputPricePer1k: 0.00015, outputPricePer1k: 0.0006 },
    'gpt-4': { inputPricePer1k: 0.03, outputPricePer1k: 0.06 },
    'gpt-4-turbo': { inputPricePer1k: 0.01, outputPricePer1k: 0.03 },
    'gpt-35-turbo': { inputPricePer1k: 0.0015, outputPricePer1k: 0.002 },
    'gpt-3.5-turbo': { inputPricePer1k: 0.0015, outputPricePer1k: 0.002 },
    'text-embedding-ada-002': { inputPricePer1k: 0.0001, outputPricePer1k: 0 },
    'text-embedding-3-small': { inputPricePer1k: 0.00002, outputPricePer1k: 0 },
    'text-embedding-3-large': { inputPricePer1k: 0.00013, outputPricePer1k: 0 },
  };

  const lower = String(modelName ?? '').toLowerCase();
  for (const [key, prices] of Object.entries(AZURE_OPENAI_MODELS)) {
    if (lower.includes(key)) return prices;
  }
  return { inputPricePer1k: 0, outputPricePer1k: 0 };
}

async function fetchTokenMetricsForResource(resource, token, timespan) {
  const resourceId = String(resource?.id ?? '');
  if (!resourceId) return null;

  try {
    const defsResp = await armGet(
      `https://management.azure.com${resourceId}/providers/microsoft.insights/metricDefinitions?api-version=2018-01-01`,
      token,
    );
    const definitions = Array.isArray(defsResp.value) ? defsResp.value : [];

    const tokenMetricNames = definitions
      .filter(def => {
        const valueName = String(def?.name?.value ?? '');
        const localName = String(def?.name?.localizedValue ?? '');
        const displayName = String(def?.displayDescription ?? '');
        return /token/i.test(valueName) || /token/i.test(localName) || /token/i.test(displayName);
      })
      .map(def => String(def?.name?.value ?? '').trim())
      .filter(Boolean);

    if (tokenMetricNames.length === 0) {
      if (String(resource?.type ?? '').toLowerCase() === 'microsoft.cognitiveservices/accounts') {
        tokenMetricNames.push('InputTokens', 'OutputTokens');
      } else {
        return null;
      }
    }

    const metricNames = Array.from(new Set(tokenMetricNames)).slice(0, 20);
    const metricsResp = await armGet(
      `https://management.azure.com${resourceId}/providers/microsoft.insights/metrics` +
      `?api-version=2023-10-01&aggregation=Total&timespan=${timespan.timespan}&interval=${encodeURIComponent(timespan.interval)}&metricnames=${encodeURIComponent(metricNames.join(','))}`,
      token,
    );

    return {
      resource,
      metrics: Array.isArray(metricsResp.value) ? metricsResp.value : [],
    };
  } catch (err) {
    console.log(`[METRICS ERROR] Resource "${resource?.name ?? resourceId}": ${err instanceof Error ? err.message : String(err)}`);
    return null;
  }
}

function resolveTimeWindow(windowKeyRaw) {
  const key = String(windowKeyRaw ?? '').trim().toLowerCase();
  const now = new Date();

  if (key === 'forever') {
    return {
      key: 'forever',
      since: new Date('2000-01-01T00:00:00.000Z'),
      until: new Date(now.getTime() + 2 * 24 * 60 * 60 * 1000),
      interval: 'P1D',
      bucket: 'day',
    };
  }

  const minutes = Number.parseInt(key, 10);
  const safeMinutes = Number.isFinite(minutes) && minutes > 0 ? minutes : (Number.parseInt(process.env.TOKENPULSE_ACTIVITY_WINDOW_DAYS ?? '30', 10) * 24 * 60);
  const since = new Date(now.getTime() - safeMinutes * 60 * 1000);
  const until = new Date(now.getTime() + 2 * 60 * 1000);

  if (safeMinutes <= 60) {
    return { key: String(safeMinutes), since, until, interval: 'PT1M', bucket: 'minute' };
  }
  if (safeMinutes <= 24 * 60) {
    return { key: String(safeMinutes), since, until, interval: 'PT15M', bucket: 'minute' };
  }
  if (safeMinutes <= 7 * 24 * 60) {
    return { key: String(safeMinutes), since, until, interval: 'PT1H', bucket: 'hour' };
  }

  return { key: String(safeMinutes), since, until, interval: 'P1D', bucket: 'day' };
}

function toBucketKey(timestamp, bucket) {
  const iso = new Date(timestamp).toISOString();
  if (bucket === 'minute') return iso.slice(0, 16) + ':00.000Z';
  if (bucket === 'hour') return iso.slice(0, 13) + ':00:00.000Z';
  return iso.slice(0, 10);
}

async function buildLocalDataset(token, subId, windowKey) {
  if (!token) {
    throw new Error('Missing delegated Entra token. Sign in and retry.');
  }
  if (!subId) {
    throw new Error('No active Azure subscription selected. Use the Azure Authentication panel to select a subscription.');
  }

  const resolvedWindow = resolveTimeWindow(windowKey);
  const timespan = {
    timespan: `${resolvedWindow.since.toISOString()}/${resolvedWindow.until.toISOString()}`,
    interval: resolvedWindow.interval,
    bucket: resolvedWindow.bucket,
  };

  const configuredTypes = String(process.env.TOKENPULSE_TOKEN_RESOURCE_TYPES ?? '')
    .split(',')
    .map(v => v.trim())
    .filter(Boolean);
  const configuredTypeSet = new Set(configuredTypes.map(v => v.toLowerCase()));
  const isTypeFilterEnabled = configuredTypeSet.size > 0;
  const maxResourceProbes = Number.parseInt(process.env.TOKENPULSE_MAX_RESOURCE_METRIC_PROBES ?? '400', 10);

  const resourcesResp = await armGet(
    `https://management.azure.com/subscriptions/${subId}/resources?api-version=2021-04-01`,
    token,
  );
  const allResources = Array.isArray(resourcesResp.value) ? resourcesResp.value : [];
  const trackedResources = isTypeFilterEnabled
    ? allResources.filter(resource => configuredTypeSet.has(String(resource?.type ?? '').toLowerCase()))
    : allResources;

  const probedResources = trackedResources.slice(0, Number.isFinite(maxResourceProbes) && maxResourceProbes > 0 ? maxResourceProbes : 400);

  if (trackedResources.length === 0) {
    if (isTypeFilterEnabled) {
      throw new Error(
        'No token-trackable Azure resources were found in this subscription for the configured services. ' +
        `Checked resource types: ${configuredTypes.join(', ')}.`,
      );
    }

    throw new Error('No Azure resources were found in this subscription.');
  }

  const metricResults = await Promise.all(probedResources.map(resource => fetchTokenMetricsForResource(resource, token, timespan)));
  const tokenResources = metricResults.filter(Boolean);

  if (tokenResources.length === 0) {
    if (isTypeFilterEnabled) {
      throw new Error(
        'No token usage metrics were found on the configured resource types in this subscription. ' +
        `Checked resource types: ${configuredTypes.join(', ')}.`,
      );
    }

    throw new Error(
      'No token usage metrics were found on resources in this subscription. ' +
      `Scanned ${probedResources.length} resources (out of ${allResources.length}).`,
    );
  }

  const providerMap = new Map();
  const modelMap = new Map();
  const projectMap = new Map();
  const usageEvents = [];
  const metricCategoryTotals = new Map();
  const metricCategoryDailyMap = new Map();
  const metricCategoryByResourceMap = new Map();
  const metricCategoryByModelMap = new Map();

  for (const entry of tokenResources) {
    const resource = entry.resource;
    const metrics = Array.isArray(entry.metrics) ? entry.metrics : [];
    const rgName = parseResourceGroup(resource.id);
    const resourceName = String(resource.name ?? rgName);
    const resourceType = String(resource.type ?? 'Unknown');
    const kind = String(resource.kind ?? 'Generic');
    const projectId = rgName;

    if (!projectMap.has(projectId)) {
      const idx = projectMap.size;
      projectMap.set(projectId, {
        id: projectId,
        name: rgName,
        color: COLORS[idx % COLORS.length],
        description: `Azure resource group · ${resourceName}`,
      });
    }

    const providerId = resourceType.toLowerCase().replace(/[^a-z0-9]/g, '-');
    if (!providerMap.has(providerId)) {
      providerMap.set(providerId, {
        id: providerId,
        name: kind && kind !== 'Generic' ? `${resourceType} (${kind})` : resourceType,
        color: COLORS[providerMap.size % COLORS.length],
      });
    }

    const modelKey = `${resource.id}/token-usage`;
    if (!modelMap.has(modelKey)) {
      const pricing = getModelPricing(resourceName);
      modelMap.set(modelKey, {
        id: modelKey.replace(/[^a-z0-9-]/gi, '-').toLowerCase(),
        providerId,
        name: resourceName,
        inputPricePer1k: pricing.inputPricePer1k,
        outputPricePer1k: pricing.outputPricePer1k,
      });
    }
    const model = modelMap.get(modelKey);

    const metricLabels = new Set();
    const dateCategoryMap = new Map();

    for (const metric of metrics) {
      const metricName = String(metric?.name?.value ?? metric?.name?.localizedValue ?? 'TokenMetric');
      metricLabels.add(metricName);
      const category = getMetricCategory(metricName);

      for (const series of metric?.timeseries ?? []) {
        for (const point of series?.data ?? []) {
          const rawValue = point.total ?? point.count ?? point.average ?? null;
          const value = Number(rawValue ?? 0);
          if (!Number.isFinite(value) || value <= 0) continue;

          const date = toBucketKey(point.timeStamp ?? new Date().toISOString(), timespan.bucket);
          if (!date) continue;

          incrementCategoryMap(metricCategoryTotals, category, value);

          const dailyKey = `${date}|${category.key}`;
          if (!metricCategoryDailyMap.has(dailyKey)) {
            metricCategoryDailyMap.set(dailyKey, {
              date,
              key: category.key,
              label: category.label,
              unit: category.unit,
              total: 0,
            });
          }
          metricCategoryDailyMap.get(dailyKey).total += value;

          const resourceKey = `${resource.id}|${category.key}`;
          if (!metricCategoryByResourceMap.has(resourceKey)) {
            metricCategoryByResourceMap.set(resourceKey, {
              resourceId: String(resource.id ?? ''),
              resourceName,
              resourceType,
              modelId: model.id,
              modelName: model.name,
              key: category.key,
              label: category.label,
              unit: category.unit,
              total: 0,
            });
          }
          metricCategoryByResourceMap.get(resourceKey).total += value;

          const modelCategoryKey = `${model.id}|${category.key}`;
          if (!metricCategoryByModelMap.has(modelCategoryKey)) {
            metricCategoryByModelMap.set(modelCategoryKey, {
              modelId: model.id,
              modelName: model.name,
              providerId,
              key: category.key,
              label: category.label,
              unit: category.unit,
              total: 0,
            });
          }
          metricCategoryByModelMap.get(modelCategoryKey).total += value;

          if (!dateCategoryMap.has(date)) dateCategoryMap.set(date, {});
          const catDay = dateCategoryMap.get(date);
          catDay[category.key] = (catDay[category.key] ?? 0) + value;

        }
      }
    }

    const purpose = Array.from(metricLabels).slice(0, 4).join(', ') || 'Token metrics';
    let evtIdx = usageEvents.length;

    for (const [date, categoryValues] of dateCategoryMap) {
      const input = Math.round(
        (categoryValues.inputTokens ?? 0)
        + (categoryValues.promptTokens ?? 0)
        + (categoryValues.cachedInputTokens ?? 0)
        + (categoryValues.audioInputTokens ?? 0)
        + (categoryValues.embeddingTokens ?? 0)
        + (categoryValues.trainingTokens ?? 0)
        + (categoryValues.validationTokens ?? 0)
        + (categoryValues.cacheReadTokens ?? 0)
        + (categoryValues.cacheWriteTokens ?? 0)
        + (categoryValues.reasoningTokens ?? 0),
      );

      const output = Math.round(
        (categoryValues.outputTokens ?? 0)
        + (categoryValues.completionTokens ?? 0)
        + (categoryValues.audioOutputTokens ?? 0),
      );

      let effectiveInput = input;
      let effectiveOutput = output;

      if (effectiveInput === 0 && effectiveOutput === 0) {
        // Fall back to total-like metrics only when no explicit input/output metrics exist.
        effectiveInput = Math.round(
          (categoryValues.totalTokens ?? 0)
          + (categoryValues.cachedTokens ?? 0)
          + (categoryValues.textTokens ?? 0)
          + (categoryValues.imageTokens ?? 0)
          + (categoryValues.otherTokenMetrics ?? 0),
        );
      }

      if (effectiveInput === 0 && effectiveOutput === 0) continue;

      const cost = (effectiveInput / 1000) * model.inputPricePer1k + (effectiveOutput / 1000) * model.outputPricePer1k;
      const eventTimestamp = date.length > 10 ? date : `${date}T12:00:00.000Z`;
      usageEvents.push({
        id: `evt-${evtIdx++}`,
        timestamp: eventTimestamp,
        projectId,
        modelId: model.id,
        inputTokens: effectiveInput,
        outputTokens: effectiveOutput,
        cost,
        source: `${resourceName} (${resourceType})`,
        purpose,
        resourceId: resource.id,
      });
    }
  }

  if (usageEvents.length === 0) {
    if (isTypeFilterEnabled) {
      throw new Error(
        'No token usage points were found in the selected time window for tracked services. ' +
        `Checked resource types: ${configuredTypes.join(', ')}.`,
      );
    }

    throw new Error(
      'Token metric definitions were found, but no non-zero token usage points were found in the selected time window. ' +
      `Scanned ${probedResources.length} resources.`,
    );
  }

  return {
    providers: Array.from(providerMap.values()),
    models: Array.from(modelMap.values()),
    projects: Array.from(projectMap.values()),
    usageEvents,
    metricCategoryTotals: serializeCategoryMap(metricCategoryTotals),
    metricCategoryDaily: Array.from(metricCategoryDailyMap.values())
      .sort((a, b) => a.date.localeCompare(b.date) || a.label.localeCompare(b.label)),
    metricCategoryByResource: Array.from(metricCategoryByResourceMap.values())
      .sort((a, b) => b.total - a.total),
    metricCategoryByModel: Array.from(metricCategoryByModelMap.values())
      .sort((a, b) => b.total - a.total),
    selectedWindow: resolvedWindow.key,
    selectedWindowSince: resolvedWindow.since.toISOString(),
    selectedWindowUntil: resolvedWindow.until.toISOString(),
    selectedWindowInterval: resolvedWindow.interval,
    selectedWindowBucket: resolvedWindow.bucket,
    alerts: [],
  };
}

function getLocalAlerts() {
  if (!localAlerts) localAlerts = [];
  return localAlerts;
}

function normalizeTenantDataError(error, subscriptionId) {
  const fallback = {
    status: 502,
    body: { error: 'Failed to load tenant data.' },
  };

  if (!(error instanceof Error)) return fallback;
  const message = error.message ?? '';

  if (message.startsWith('No active Azure subscription')) {
    return {
      status: 400,
      body: {
        error: 'No active Azure subscription selected. Use the Azure Authentication panel to select a subscription.',
      },
    };
  }

  if (
    message.startsWith('No token-trackable Azure resources') ||
    message.startsWith('No token usage metrics were found') ||
    message.startsWith('No token usage points were found')
  ) {
    return {
      status: 404,
      body: { error: message },
    };
  }

  if (message.startsWith('ARM 403:')) {
    const details = message.slice('ARM 403:'.length).trim();
    let azureCode = '';
    try {
      const parsed = JSON.parse(details);
      azureCode = String(parsed?.error?.code ?? '');
    } catch {
      azureCode = '';
    }

    if (azureCode === 'AuthorizationFailed' || details.includes('AuthorizationFailed')) {
      return {
        status: 403,
        body: {
          error:
            'Access denied for the selected subscription. ' +
            'Switch to a subscription where you have Reader access, or ask an administrator to grant Reader on the subscription/resource group with tracked token resources.',
          code: 'AuthorizationFailed',
          subscriptionId: subscriptionId ?? null,
        },
      };
    }
  }

  return {
    status: 502,
    body: { error: message || 'Failed to load tenant data.' },
  };
}

async function forwardJson(res, endpointPath, init = {}) {
  const response = await fetch(`${upstreamBaseUrl}${endpointPath}`, {
    ...init,
    headers: buildHeaders(init.headers ?? {}),
    cache: 'no-store',
  });
  const contentType = response.headers.get('content-type') ?? 'application/json';
  noStore(res);
  res.status(response.status);
  res.setHeader('Content-Type', contentType);
  res.send(await response.text());
}

app.get('/api/auth/status', (_req, res) => {
  noStore(res);
  buildServerAuthResponse()
    .then(payload => res.json(payload))
    .catch(() => res.json({ authenticated: false, tenants: [], subscriptions: [], selectedTenantId: null, selectedSubscriptionId: null }));
});

app.post('/api/auth/login', async (_req, res) => {
  noStore(res);
  try {
    const credentialOptions = {};
    if (process.env.AZURE_TENANT_ID) credentialOptions.tenantId = process.env.AZURE_TENANT_ID;
    if (process.env.AZURE_CLIENT_ID) credentialOptions.clientId = process.env.AZURE_CLIENT_ID;
    serverCredential = new InteractiveBrowserCredential(credentialOptions);
    await getServerArmAccessToken(true);
    const payload = await buildServerAuthResponse();
    res.json(payload);
  } catch (err) {
    console.error('[auth/login] Interactive Entra login failed:', err);
    serverCredential = null;
    cachedServerToken = null;
    cachedServerTokenExpiresAt = 0;
    res.status(500).json({
      authenticated: false,
      tenants: [],
      subscriptions: [],
      selectedTenantId: null,
      selectedSubscriptionId: null,
      error: err instanceof Error ? err.message : 'Entra login failed',
    });
  }
});

app.post('/api/auth/logout', (_req, res) => {
  noStore(res);
  serverCredential = null;
  cachedServerToken = null;
  cachedServerTokenExpiresAt = 0;
  currentSubscriptionId = null;
  res.json({ authenticated: false, tenants: [], subscriptions: [], selectedTenantId: null, selectedSubscriptionId: null });
});

app.get('/api/auth/subscriptions', async (_req, res) => {
  noStore(res);
  try {
    const token = await getServerArmAccessToken();
    if (!token) {
      res.status(401).json({ error: 'Not authenticated. Please login first.' });
      return;
    }

    const subscriptions = await listArmSubscriptions(token);
    res.json({ subscriptions, count: subscriptions.length, timestamp: new Date().toISOString() });
  } catch (err) {
    res.status(500).json({ error: err instanceof Error ? err.message : 'Failed to load subscriptions' });
  }
});

app.post('/api/auth/subscription', async (req, res) => {
  noStore(res);
  try {
    const nextId = String(req.body?.subscriptionId ?? '').trim();
    if (!nextId) {
      res.status(400).json({ error: 'subscriptionId is required' });
      return;
    }

    const token = await getServerArmAccessToken();
    if (!token) {
      res.status(401).json({ error: 'Not authenticated. Please login first.' });
      return;
    }

    const subscriptions = await listArmSubscriptions(token);
    const found = subscriptions.find(sub => sub.id === nextId);
    if (!found) {
      res.status(404).json({ error: 'Subscription not found or inaccessible.' });
      return;
    }

    currentSubscriptionId = nextId;
    const payload = await buildServerAuthResponse();
    res.json(payload);
  } catch (err) {
    res.status(400).json({ error: err instanceof Error ? err.message : 'Failed to select subscription' });
  }
});

app.post('/api/auth/switch-subscription/:subscriptionId', async (req, res) => {
  noStore(res);
  try {
    const nextId = String(req.params.subscriptionId ?? '').trim();
    if (!nextId) {
      res.status(400).json({ error: 'subscriptionId is required' });
      return;
    }

    const token = await getServerArmAccessToken();
    if (!token) {
      res.status(401).json({ error: 'Not authenticated. Please login first.' });
      return;
    }

    const subscriptions = await listArmSubscriptions(token);
    const found = subscriptions.find(sub => sub.id === nextId);
    if (!found) {
      res.status(404).json({ error: 'Subscription not found or inaccessible.' });
      return;
    }

    currentSubscriptionId = nextId;
    const payload = await buildServerAuthResponse();
    res.json(payload);
  } catch (err) {
    res.status(400).json({ error: err instanceof Error ? err.message : 'Failed to switch subscription' });
  }
});

app.get('/healthz', (_req, res) => {
  noStore(res);
  res.json({ ok: true, liveMode: true, upstreamConfigured: Boolean(upstreamBaseUrl) });
});

app.get('/api/tokenpulse', async (req, res) => {
  noStore(res);
  const delegatedToken = getAuthHeaderToken(req);
  const selectedSubscriptionId = String(req.headers['x-azure-subscription-id'] ?? '').trim() || null;
  const selectedWindow = String(req.query.window ?? '').trim() || null;
  const activeToken = delegatedToken ?? await getServerArmAccessToken();
  const activeSubscription = selectedSubscriptionId ?? currentSubscriptionId;

  if (!activeToken) {
    res.status(401).type('text/plain').send('Missing delegated Entra token. Please login again.');
    return;
  }

  try {
    if (upstreamBaseUrl) {
      const upstreamPath = `/tokenpulse${selectedWindow ? `?window=${encodeURIComponent(selectedWindow)}` : ''}`;
      await forwardJson(res, upstreamPath, {
        method: 'GET',
        headers: {
          ...(activeToken ? { Authorization: `Bearer ${activeToken}` } : {}),
          ...(activeSubscription ? { 'X-Azure-Subscription-Id': activeSubscription } : {}),
        },
      });
    } else {
      const dataset = await buildLocalDataset(activeToken, activeSubscription, selectedWindow);
      dataset.alerts = getLocalAlerts();
      res.json(dataset);
    }
  } catch (error) {
    const normalized = normalizeTenantDataError(error, activeSubscription);
    res.status(normalized.status).json(normalized.body);
  }
});

app.post('/api/tokenpulse/alerts', async (req, res) => {
  noStore(res);
  try {
    if (upstreamBaseUrl) {
      await forwardJson(res, '/tokenpulse/alerts', { method: 'POST', body: JSON.stringify(req.body ?? {}) });
    } else {
      const body = req.body ?? {};
      const created = {
        id: `alert-${Date.now()}`,
        name: String(body.name ?? 'New Alert'),
        scope: body.scope === 'project' ? 'project' : 'global',
        projectId: body.projectId,
        thresholdUsd: Number(body.thresholdUsd) || 100,
        windowDays: Number(body.windowDays) || 30,
        enabled: body.enabled !== false,
      };
      if (!localAlerts) localAlerts = [];
      localAlerts = [...localAlerts, created];
      res.status(201).json(created);
    }
  } catch (error) {
    res.status(502).json({ error: error instanceof Error ? error.message : 'Upstream request failed.' });
  }
});

app.put('/api/tokenpulse/alerts/:id', async (req, res) => {
  noStore(res);
  try {
    if (upstreamBaseUrl) {
      await forwardJson(res, `/tokenpulse/alerts/${encodeURIComponent(req.params.id)}`, { method: 'PUT', body: JSON.stringify(req.body ?? {}) });
    } else {
      const alertId = req.params.id;
      let updated;
      localAlerts = (localAlerts ?? []).map(a => {
        if (a.id !== alertId) return a;
        updated = { ...a, ...req.body, id: alertId };
        return updated;
      });
      if (!updated) {
        res.status(404).json({ error: 'Alert not found' });
        return;
      }
      res.json(updated);
    }
  } catch (error) {
    res.status(502).json({ error: error instanceof Error ? error.message : 'Upstream request failed.' });
  }
});

app.delete('/api/tokenpulse/alerts/:id', async (req, res) => {
  noStore(res);
  try {
    if (upstreamBaseUrl) {
      await forwardJson(res, `/tokenpulse/alerts/${encodeURIComponent(req.params.id)}`, { method: 'DELETE' });
    } else {
      localAlerts = (localAlerts ?? []).filter(a => a.id !== req.params.id);
      res.json({ ok: true, id: req.params.id });
    }
  } catch (error) {
    res.status(502).json({ error: error instanceof Error ? error.message : 'Upstream request failed.' });
  }
});

app.use((req, res, next) => {
  if (req.path.startsWith('/api/') || req.path === '/healthz') {
    next();
    return;
  }

  noStore(res);
  res.sendFile(indexHtmlPath, err => {
    if (err) {
      res.status(404).json({ error: 'Frontend assets not found. Ensure dist/ is deployed.' });
    }
  });
});

app.listen(port, () => {
  console.log(`TokenPulse API proxy listening on http://127.0.0.1:${port}`);
});
