import type { Model, Project, Provider, TokenPulseDataset, UsageEvent } from './types';

export function getModel(models: Model[], id: string) {
  return models.find(m => m.id === id);
}

export function getProject(projects: Project[], id: string) {
  return projects.find(p => p.id === id);
}

export function getProvider(providers: Provider[], id: string) {
  return providers.find(p => p.id === id);
}

export function getProviderByModelId(models: Model[], providers: Provider[], modelId: string) {
  const model = getModel(models, modelId);
  if (!model) return undefined;
  return getProvider(providers, model.providerId);
}

export function filterEvents(
  events: UsageEvent[],
  models: Model[],
  opts: {
    providerId?: string;
    projectId?: string;
    startDate?: Date;
    endDate?: Date;
  },
): UsageEvent[] {
  return events.filter(e => {
    if (opts.projectId && e.projectId !== opts.projectId) return false;
    if (opts.providerId) {
      const model = getModel(models, e.modelId);
      if (!model || model.providerId !== opts.providerId) return false;
    }
    if (opts.startDate && e.timestamp < opts.startDate) return false;
    if (opts.endDate && e.timestamp > opts.endDate) return false;
    return true;
  });
}

export function sumCost(events: UsageEvent[]): number {
  return events.reduce((s, e) => s + e.cost, 0);
}

export function sumTokens(events: UsageEvent[]): number {
  return events.reduce((s, e) => s + e.inputTokens + e.outputTokens, 0);
}

export function getDailyBuckets(
  events: UsageEvent[],
  models: Model[],
  days = 30,
  now = new Date(),
): Array<{ date: string; tokens: number; cost: number; byProvider: Record<string, number> }> {
  const buckets: Map<string, { tokens: number; cost: number; byProvider: Record<string, number> }> = new Map();

  for (let i = 0; i < days; i++) {
    const d = new Date(now);
    d.setDate(d.getDate() - (days - 1 - i));
    const key = d.toISOString().slice(0, 10);
    buckets.set(key, { tokens: 0, cost: 0, byProvider: {} });
  }

  for (const e of events) {
    const key = e.timestamp.toISOString().slice(0, 10);
    const bucket = buckets.get(key);
    if (!bucket) continue;
    bucket.tokens += e.inputTokens + e.outputTokens;
    bucket.cost += e.cost;
    const model = getModel(models, e.modelId);
    if (model) {
      bucket.byProvider[model.providerId] = (bucket.byProvider[model.providerId] ?? 0) + e.cost;
    }
  }

  return Array.from(buckets.entries()).map(([date, v]) => ({ date, ...v }));
}

export function getCostByProject(data: TokenPulseDataset): Array<{ project: Project; cost: number; tokens: number }> {
  return data.projects
    .map(project => {
      const evts = filterEvents(data.usageEvents, data.models, { projectId: project.id });
      return { project, cost: sumCost(evts), tokens: sumTokens(evts) };
    })
    .sort((a, b) => b.cost - a.cost || b.tokens - a.tokens);
}

export function getCostByModel(data: TokenPulseDataset): Array<{ model: Model; cost: number; tokens: number; events: number }> {
  return data.models
    .map(model => {
      const evts = data.usageEvents.filter(e => e.modelId === model.id);
      return { model, cost: sumCost(evts), tokens: sumTokens(evts), events: evts.length };
    })
    .filter(d => d.tokens > 0 || d.events > 0)
    .sort((a, b) => b.cost - a.cost || b.tokens - a.tokens);
}

export function getSparklineForProject(data: TokenPulseDataset, projectId: string, days = 14, now = new Date()): number[] {
  const evts = filterEvents(data.usageEvents, data.models, { projectId });
  const buckets = getDailyBuckets(evts, data.models, days, now);
  return buckets.map(b => b.tokens);
}

export function getEventsInWindow(events: UsageEvent[], days: number, now = new Date()): UsageEvent[] {
  const cutoff = new Date(now);
  cutoff.setDate(cutoff.getDate() - days);
  return events.filter(e => e.timestamp >= cutoff);
}

export function formatCost(usd: number): string {
  if (usd === 0) return '$0.00';
  if (usd < 0.01) return `$${usd.toFixed(4)}`;
  if (usd < 1) return `$${usd.toFixed(3)}`;
  return `$${usd.toFixed(2)}`;
}

export function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return n.toString();
}

export function formatNumber(n: number): string {
  return n.toLocaleString();
}
