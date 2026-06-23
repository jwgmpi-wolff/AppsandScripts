import type { Provider, Model, Project, UsageEvent, Alert } from './types';

export const providers: Provider[] = [
  { id: 'azure-openai', name: 'Azure OpenAI', color: '#0078d4' },
  { id: 'openai', name: 'OpenAI', color: '#10a37f' },
  { id: 'anthropic', name: 'Anthropic', color: '#d97706' },
  { id: 'google-gemini', name: 'Google Gemini', color: '#4285f4' },
  { id: 'cohere', name: 'Cohere', color: '#8b5cf6' },
];

export const models: Model[] = [
  { id: 'gpt-4o', providerId: 'azure-openai', name: 'GPT-4o', inputPricePer1k: 0.005, outputPricePer1k: 0.015 },
  { id: 'gpt-4o-mini', providerId: 'azure-openai', name: 'GPT-4o mini', inputPricePer1k: 0.00015, outputPricePer1k: 0.0006 },
  { id: 'o1-preview', providerId: 'openai', name: 'o1-preview', inputPricePer1k: 0.015, outputPricePer1k: 0.06 },
  { id: 'gpt-4-turbo', providerId: 'openai', name: 'GPT-4 Turbo', inputPricePer1k: 0.01, outputPricePer1k: 0.03 },
  { id: 'claude-3-5-sonnet', providerId: 'anthropic', name: 'Claude 3.5 Sonnet', inputPricePer1k: 0.003, outputPricePer1k: 0.015 },
  { id: 'claude-3-opus', providerId: 'anthropic', name: 'Claude 3 Opus', inputPricePer1k: 0.015, outputPricePer1k: 0.075 },
  { id: 'gemini-1-5-pro', providerId: 'google-gemini', name: 'Gemini 1.5 Pro', inputPricePer1k: 0.00125, outputPricePer1k: 0.005 },
  { id: 'command-r-plus', providerId: 'cohere', name: 'Command R+', inputPricePer1k: 0.003, outputPricePer1k: 0.015 },
];

export const projects: Project[] = [
  { id: 'proj-alpha', name: 'Project Alpha', color: '#6366f1', description: 'Customer support chatbot' },
  { id: 'proj-beta', name: 'Project Beta', color: '#ec4899', description: 'Internal knowledge base' },
  { id: 'proj-gamma', name: 'Project Gamma', color: '#f59e0b', description: 'Code review assistant' },
  { id: 'proj-delta', name: 'Project Delta', color: '#14b8a6', description: 'Marketing copy generator' },
  { id: 'proj-epsilon', name: 'Project Epsilon', color: '#f97316', description: 'Data analysis pipeline' },
];

function randomBetween(min: number, max: number): number {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function seedRandom(seed: number): () => number {
  let s = seed;
  return () => {
    s = (s * 1664525 + 1013904223) & 0xffffffff;
    return (s >>> 0) / 0xffffffff;
  };
}

export function generateUsageEvents(): UsageEvent[] {
  const rand = seedRandom(42);
  const events: UsageEvent[] = [];
  const now = new Date('2026-06-23T23:59:59Z');
  const msPerDay = 86400000;
  const modelById = new Map(models.map(m => [m.id, m]));
  const projectById = new Map(projects.map(p => [p.id, p]));

  const modelWeights = [
    { id: 'gpt-4o', weight: 30 },
    { id: 'gpt-4o-mini', weight: 25 },
    { id: 'claude-3-5-sonnet', weight: 20 },
    { id: 'gpt-4-turbo', weight: 10 },
    { id: 'o1-preview', weight: 5 },
    { id: 'claude-3-opus', weight: 4 },
    { id: 'gemini-1-5-pro', weight: 4 },
    { id: 'command-r-plus', weight: 2 },
  ];

  const projectWeights = [
    { id: 'proj-alpha', weight: 35 },
    { id: 'proj-beta', weight: 25 },
    { id: 'proj-gamma', weight: 20 },
    { id: 'proj-delta', weight: 12 },
    { id: 'proj-epsilon', weight: 8 },
  ];

  function weightedPick<T extends { weight: number }>(items: T[]): T {
    const total = items.reduce((s, i) => s + i.weight, 0);
    let r = rand() * total;
    for (const item of items) {
      r -= item.weight;
      if (r <= 0) return item;
    }
    return items[items.length - 1];
  }

  let id = 1;
  for (let day = 0; day < 30; day++) {
    const dayMs = now.getTime() - (29 - day) * msPerDay;
    // Weekend dip
    const dayOfWeek = new Date(dayMs).getDay();
    const isWeekend = dayOfWeek === 0 || dayOfWeek === 6;
    const eventsPerDay = isWeekend
      ? randomBetween(30, 60)
      : randomBetween(80, 160);

    for (let e = 0; e < eventsPerDay; e++) {
      const eventMs = dayMs - rand() * msPerDay;
      const pickedModel = weightedPick(modelWeights);
      const pickedProject = weightedPick(projectWeights);
      const model = modelById.get(pickedModel.id);
      const project = projectById.get(pickedProject.id);
      if (!model || !project) {
        continue;
      }
      const inputTokens = Math.floor(rand() * 3800 + 200);
      const outputTokens = Math.floor(rand() * 1800 + 100);
      const cost =
        (inputTokens / 1000) * model.inputPricePer1k +
        (outputTokens / 1000) * model.outputPricePer1k;

      events.push({
        id: `evt-${id++}`,
        timestamp: new Date(eventMs),
        projectId: project.id,
        modelId: model.id,
        inputTokens,
        outputTokens,
        cost,
      });
    }
  }

  return events.sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime());
}

export const usageEvents: UsageEvent[] = generateUsageEvents();

export const alerts: Alert[] = [
  { id: 'alert-1', name: 'Monthly Global Budget', scope: 'global', thresholdUsd: 500, windowDays: 30, enabled: true },
  { id: 'alert-2', name: 'Project Alpha Weekly', scope: 'project', projectId: 'proj-alpha', thresholdUsd: 150, windowDays: 7, enabled: true },
  { id: 'alert-3', name: 'Project Beta Monthly', scope: 'project', projectId: 'proj-beta', thresholdUsd: 200, windowDays: 30, enabled: true },
  { id: 'alert-4', name: 'Daily Spike Guard', scope: 'global', thresholdUsd: 50, windowDays: 1, enabled: false },
];
