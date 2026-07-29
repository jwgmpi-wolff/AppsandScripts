import type { Alert, TokenPulseDataset, UsageEvent } from './types';

const DATA_URL = import.meta.env.VITE_TOKENPULSE_DATA_URL ?? '/api/tokenpulse';
const REQUEST_TIMEOUT_MS = 30000;

function buildAuthHeaders(accessToken?: string | null, subscriptionId?: string | null): HeadersInit {
  return {
    ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    ...(subscriptionId ? { 'X-Azure-Subscription-Id': subscriptionId } : {}),
  };
}

function normalizeUsageEvents(events: Array<Omit<UsageEvent, 'timestamp'> & { timestamp: string | Date }>): UsageEvent[] {
  return events.map(event => ({
    ...event,
    timestamp: event.timestamp instanceof Date ? event.timestamp : new Date(event.timestamp),
  }));
}

function normalizeDataset(raw: TokenPulseDataset): TokenPulseDataset {
  return {
    ...raw,
    usageEvents: normalizeUsageEvents(raw.usageEvents as Array<Omit<UsageEvent, 'timestamp'> & { timestamp: string | Date }>),
  };
}

async function fetchJson<T>(input: RequestInfo | URL, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  const response = await fetch(input, {
    ...init,
    cache: 'no-store',
    signal: controller.signal,
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
  });

  clearTimeout(timeout);

  if (!response.ok) {
    const text = await response.text();
    if (!text) {
      if (response.status === 502) {
        throw new Error('Request failed with status 502 (Bad Gateway). The API server may be down or restarting. Start/restart the backend with npm run start:api (or npm run api:dev).');
      }
      throw new Error(`Request failed with status ${response.status}`);
    }

    let message = text;
    try {
      const parsed = JSON.parse(text) as { error?: string; message?: string };
      message = parsed.error ?? parsed.message ?? text;
    } catch {
      // Keep raw text message when response is not JSON.
    }

    if (response.status === 502 && /bad gateway|proxy/i.test(message)) {
      message = 'Request failed with status 502 (Bad Gateway). The API server may be down or restarting. Start/restart the backend with npm run start:api (or npm run api:dev).';
    }

    throw new Error(message);
  }

  return (await response.json()) as T;
}

export async function fetchLiveDataset(subscriptionId?: string | null, accessToken?: string | null, timeWindow?: string): Promise<TokenPulseDataset> {
  const url = new URL(DATA_URL, globalThis.window.location.origin);
  if (timeWindow && timeWindow.trim()) {
    url.searchParams.set('window', timeWindow.trim());
  }

  const raw = await fetchJson<TokenPulseDataset>(url.toString(), {
    headers: buildAuthHeaders(accessToken, subscriptionId),
  });
  return normalizeDataset(raw);
}

export async function createAlert(alert: Omit<Alert, 'id'>, subscriptionId?: string | null, accessToken?: string | null): Promise<Alert> {
  return fetchJson<Alert>(`${DATA_URL}/alerts`, {
    method: 'POST',
    headers: buildAuthHeaders(accessToken, subscriptionId),
    body: JSON.stringify(alert),
  });
}

export async function updateAlert(alert: Alert, subscriptionId?: string | null, accessToken?: string | null): Promise<Alert> {
  return fetchJson<Alert>(`${DATA_URL}/alerts/${encodeURIComponent(alert.id)}`, {
    method: 'PUT',
    headers: buildAuthHeaders(accessToken, subscriptionId),
    body: JSON.stringify(alert),
  });
}

export async function removeAlert(alertId: string, subscriptionId?: string | null, accessToken?: string | null): Promise<void> {
  await fetchJson<{ ok: true }>(`${DATA_URL}/alerts/${encodeURIComponent(alertId)}`, {
    method: 'DELETE',
    headers: buildAuthHeaders(accessToken, subscriptionId),
  });
}
