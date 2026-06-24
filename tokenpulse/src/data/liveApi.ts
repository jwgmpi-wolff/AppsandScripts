import type { Alert, TokenPulseDataset, UsageEvent } from './types';

const DATA_URL = import.meta.env.VITE_TOKENPULSE_DATA_URL ?? '/api/tokenpulse';

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
  const response = await fetch(input, {
    ...init,
    cache: 'no-store',
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed with status ${response.status}`);
  }

  return (await response.json()) as T;
}

export async function fetchLiveDataset(): Promise<TokenPulseDataset> {
  const raw = await fetchJson<TokenPulseDataset>(DATA_URL);
  return normalizeDataset(raw);
}

export async function createAlert(alert: Omit<Alert, 'id'>): Promise<Alert> {
  return fetchJson<Alert>(`${DATA_URL}/alerts`, {
    method: 'POST',
    body: JSON.stringify(alert),
  });
}

export async function updateAlert(alert: Alert): Promise<Alert> {
  return fetchJson<Alert>(`${DATA_URL}/alerts/${encodeURIComponent(alert.id)}`, {
    method: 'PUT',
    body: JSON.stringify(alert),
  });
}

export async function removeAlert(alertId: string): Promise<void> {
  await fetchJson<{ ok: true }>(`${DATA_URL}/alerts/${encodeURIComponent(alertId)}`, {
    method: 'DELETE',
  });
}
