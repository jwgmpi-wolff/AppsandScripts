export type ProviderName = 'Azure OpenAI' | 'OpenAI' | 'Anthropic' | 'Google Gemini' | 'Cohere';

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
