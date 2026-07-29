export type ProviderName = string;

export interface Provider {
  id: string;
  name: ProviderName;
  color: string;
}

export interface Model {
  id: string;
  providerId: string;
  name: string;
  inputPricePer1k: number;  // USD per 1k tokens
  outputPricePer1k: number; // USD per 1k tokens
}

export interface Project {
  id: string;
  name: string;
  color: string;
  description: string;
}

export interface UsageEvent {
  id: string;
  timestamp: Date;
  projectId: string;
  modelId: string;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  source?: string;
  purpose?: string;
  resourceId?: string;
  operationName?: string;
}

export interface Alert {
  id: string;
  name: string;
  scope: 'global' | 'project';
  projectId?: string;
  thresholdUsd: number;
  windowDays: number;
  enabled: boolean;
}

export interface MetricCategoryTotal {
  key: string;
  label: string;
  unit: 'tokens' | 'perMinute' | 'percent' | string;
  total: number;
}

export interface MetricCategoryDailyPoint extends MetricCategoryTotal {
  date: string;
}

export interface MetricCategoryResourceBreakdown extends MetricCategoryTotal {
  resourceId: string;
  resourceName: string;
  resourceType: string;
  modelId: string;
  modelName: string;
}

export interface MetricCategoryModelBreakdown extends MetricCategoryTotal {
  modelId: string;
  modelName: string;
  providerId: string;
}

export interface TokenPulseDataset {
  providers: Provider[];
  models: Model[];
  projects: Project[];
  usageEvents: UsageEvent[];
  metricCategoryTotals?: MetricCategoryTotal[];
  metricCategoryDaily?: MetricCategoryDailyPoint[];
  metricCategoryByResource?: MetricCategoryResourceBreakdown[];
  metricCategoryByModel?: MetricCategoryModelBreakdown[];
  selectedWindow?: string;
  selectedWindowSince?: string;
  selectedWindowUntil?: string;
  selectedWindowInterval?: string;
  selectedWindowBucket?: 'minute' | 'hour' | 'day' | string;
  alerts: Alert[];
}
