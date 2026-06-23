import { usageEvents, models, projects, providers } from './sampleData';
import type { UsageEvent } from './types';

export function getModel(id: string) {
  return models.find(m => m.id === id);
}

export function getProject(id: string) {
  return projects.find(p => p.id === id);
}

export function getProvider(id: string) {
  return providers.find(p => p.id === id);
}

export function getProviderByModelId(modelId: string) {
  const model = getModel(modelId);
  if (!model) return undefined;
  return getProvider(model.providerId);
}

export function filterEvents(opts: {
  providerId?: string;
  projectId?: string;
  startDate?: Date;
  endDate?: Date;
}): UsageEvent[] {
  return usageEvents.filter(e => {
    if (opts.projectId && e.projectId !== opts.projectId) return false;
    if (opts.providerId) {
      const model = getModel(e.modelId);
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

export function getDailyBuckets(events: UsageEvent[], days = 30): Array<{ date: string; tokens: number; cost: number; byProvider: Record<string, number> }> {
  const buckets: Map<string, { tokens: number; cost: number; byProvider: Record<string, number> }> = new Map();

  const now = new Date('2026-06-23T23:59:59Z');
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
    const model = getModel(e.modelId);
    if (model) {
      bucket.byProvider[model.providerId] = (bucket.byProvider[model.providerId] ?? 0) + e.cost;
    }
  }

  return Array.from(buckets.entries()).map(([date, v]) => ({ date, ...v }));
}

export function getCostByProject(): Array<{ project: typeof projects[0]; cost: number; tokens: number }> {
  return projects.map(project => {
    const evts = filterEvents({ projectId: project.id });
    return { project, cost: sumCost(evts), tokens: sumTokens(evts) };
  }).sort((a, b) => b.cost - a.cost);
}

export function getCostByModel(): Array<{ model: typeof models[0]; cost: number; tokens: number; events: number }> {
  return models.map(model => {
    const evts = usageEvents.filter(e => e.modelId === model.id);
    return { model, cost: sumCost(evts), tokens: sumTokens(evts), events: evts.length };
  }).sort((a, b) => b.cost - a.cost);
}

export function getSparklineForProject(projectId: string, days = 14): number[] {
  const evts = filterEvents({ projectId });
  const buckets = getDailyBuckets(evts, days);
  return buckets.map(b => b.tokens);
}

export function getEventsInWindow(days: number): UsageEvent[] {
  const cutoff = new Date('2026-06-23T23:59:59Z');
  cutoff.setDate(cutoff.getDate() - days);
  return usageEvents.filter(e => e.timestamp >= cutoff);
}

export function formatCost(usd: number): string {
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
