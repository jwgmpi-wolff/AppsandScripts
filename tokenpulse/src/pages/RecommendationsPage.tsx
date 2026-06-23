import { useMemo, useState } from 'react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { usageEvents, models, projects } from '@/data/sampleData';
import { formatCost, formatTokens, formatNumber } from '@/data/queries';
import { Lightbulb, TrendingDown, Zap, ArrowRightLeft } from 'lucide-react';

interface Recommendation {
  id: string;
  title: string;
  description: string;
  estimatedMonthlySavingsUsd: number;
  confidence: 'High' | 'Medium';
  impact: 'High' | 'Medium';
  details: string[];
}

function modelById(id: string) {
  return models.find(m => m.id === id);
}

function projectById(id: string) {
  return projects.find(p => p.id === id);
}

export function RecommendationsPage() {
  const [appliedPolicies, setAppliedPolicies] = useState<Set<string>>(new Set());

  const totalCost = useMemo(
    () => usageEvents.reduce((sum, e) => sum + e.cost, 0),
    [],
  );

  const totalInputTokens = useMemo(
    () => usageEvents.reduce((sum, e) => sum + e.inputTokens, 0),
    [],
  );

  const totalOutputTokens = useMemo(
    () => usageEvents.reduce((sum, e) => sum + e.outputTokens, 0),
    [],
  );

  const recommendations = useMemo<Recommendation[]>(() => {
    const recs: Recommendation[] = [];

    const modelTotals = new Map<string, { events: number; inputTokens: number; outputTokens: number; cost: number }>();
    for (const evt of usageEvents) {
      const current = modelTotals.get(evt.modelId) ?? { events: 0, inputTokens: 0, outputTokens: 0, cost: 0 };
      current.events += 1;
      current.inputTokens += evt.inputTokens;
      current.outputTokens += evt.outputTokens;
      current.cost += evt.cost;
      modelTotals.set(evt.modelId, current);
    }

    const expensiveModelIds = ['gpt-4o', 'gpt-4-turbo', 'o1-preview', 'claude-3-opus'];
    let expensiveSpend = 0;
    for (const modelId of expensiveModelIds) {
      expensiveSpend += modelTotals.get(modelId)?.cost ?? 0;
    }

    if (expensiveSpend > 0) {
      const conservativeShift = 0.35;
      const realizedSavingsRate = 0.65;
      const estimatedSavings = expensiveSpend * conservativeShift * realizedSavingsRate;

      recs.push({
        id: 'tiered-routing',
        title: 'Route simple traffic to cheaper models first',
        description:
          'A significant share of spend is on premium models. Add a model routing policy so low-risk requests use cheaper models by default, with fallback to premium only when needed.',
        estimatedMonthlySavingsUsd: estimatedSavings,
        confidence: 'High',
        impact: 'High',
        details: [
          `${((expensiveSpend / totalCost) * 100).toFixed(1)}% of current spend is on premium models`,
          `Start with a 35% shift target from premium to budget models`,
          'Gate fallback by quality checks (hallucination score, refusal rate, user retries)',
        ],
      });
    }

    const outputHeavyRatio = totalInputTokens > 0 ? totalOutputTokens / totalInputTokens : 0;
    if (outputHeavyRatio > 0.45) {
      const outputDrivenCost = totalCost * Math.min(0.65, outputHeavyRatio / (1 + outputHeavyRatio));
      const estimatedSavings = outputDrivenCost * 0.22;

      recs.push({
        id: 'trim-output',
        title: 'Reduce long responses and cap max output tokens',
        description:
          'Output tokens are a major cost driver. Tighter response-length controls and concise answer prompts can reduce spend without changing model quality.',
        estimatedMonthlySavingsUsd: estimatedSavings,
        confidence: 'High',
        impact: 'Medium',
        details: [
          `Current output/input ratio is ${outputHeavyRatio.toFixed(2)}x`,
          'Introduce endpoint-level max_output_tokens defaults',
          'Use short-form response modes for summaries, classifications, and routing tasks',
        ],
      });
    }

    const projectTotals = new Map<string, { cost: number; tokens: number; events: number }>();
    for (const evt of usageEvents) {
      const current = projectTotals.get(evt.projectId) ?? { cost: 0, tokens: 0, events: 0 };
      current.cost += evt.cost;
      current.tokens += evt.inputTokens + evt.outputTokens;
      current.events += 1;
      projectTotals.set(evt.projectId, current);
    }

    const topProject = Array.from(projectTotals.entries())
      .map(([projectId, stats]) => ({ projectId, ...stats }))
      .sort((a, b) => b.cost - a.cost)[0];

    if (topProject) {
      const estimatedSavings = topProject.cost * 0.12;
      const projectName = projectById(topProject.projectId)?.name ?? topProject.projectId;

      recs.push({
        id: 'project-budget-guardrails',
        title: `Add spend guardrails for ${projectName}`,
        description:
          'Your top project drives the largest share of cost. Add project-level budgets, alerting, and automatic traffic throttles during spikes.',
        estimatedMonthlySavingsUsd: estimatedSavings,
        confidence: 'Medium',
        impact: 'Medium',
        details: [
          `${projectName} currently accounts for ${((topProject.cost / totalCost) * 100).toFixed(1)}% of spend`,
          'Set per-project daily and weekly hard limits',
          'Throttle non-urgent requests when burn rate exceeds threshold',
        ],
      });
    }

    return recs.sort((a, b) => b.estimatedMonthlySavingsUsd - a.estimatedMonthlySavingsUsd);
  }, [totalCost, totalInputTokens, totalOutputTokens]);

  const estimatedTotalSavings = useMemo(
    () => recommendations.reduce((sum, r) => sum + r.estimatedMonthlySavingsUsd, 0),
    [recommendations],
  );

  const appliedSavings = useMemo(
    () => recommendations
      .filter(r => appliedPolicies.has(r.id))
      .reduce((sum, r) => sum + r.estimatedMonthlySavingsUsd, 0),
    [appliedPolicies, recommendations],
  );

  const projectedSpend = useMemo(
    () => Math.max(0, totalCost - appliedSavings),
    [appliedSavings, totalCost],
  );

  const topModelsByCost = useMemo(() => {
    const byModel = new Map<string, { cost: number; tokens: number; events: number }>();
    for (const evt of usageEvents) {
      const current = byModel.get(evt.modelId) ?? { cost: 0, tokens: 0, events: 0 };
      current.cost += evt.cost;
      current.tokens += evt.inputTokens + evt.outputTokens;
      current.events += 1;
      byModel.set(evt.modelId, current);
    }

    return Array.from(byModel.entries())
      .map(([modelId, stats]) => ({
        modelId,
        modelName: modelById(modelId)?.name ?? modelId,
        ...stats,
      }))
      .sort((a, b) => b.cost - a.cost)
      .slice(0, 4);
  }, []);

  const allPolicyIds = useMemo(
    () => recommendations.map(r => r.id),
    [recommendations],
  );

  function togglePolicy(policyId: string) {
    setAppliedPolicies(prev => {
      const next = new Set(prev);
      if (next.has(policyId)) {
        next.delete(policyId);
      } else {
        next.add(policyId);
      }
      return next;
    });
  }

  function applyAllPolicies() {
    setAppliedPolicies(new Set(allPolicyIds));
  }

  function resetPolicySimulation() {
    setAppliedPolicies(new Set());
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
        <h1 className="text-2xl font-bold text-slate-900">Cost Recommendations</h1>
        <p className="text-slate-500 mt-1">
          Actionable ways to reduce token spend based on your current usage profile
        </p>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={applyAllPolicies}
            className="px-3 py-1.5 text-xs font-medium rounded-lg border border-indigo-200 bg-indigo-50 text-indigo-700 hover:bg-indigo-100 transition-colors"
            disabled={appliedPolicies.size === allPolicyIds.length || allPolicyIds.length === 0}
          >
            Apply all
          </button>
          <button
            type="button"
            onClick={resetPolicySimulation}
            className="px-3 py-1.5 text-xs font-medium rounded-lg border border-slate-200 bg-white text-slate-700 hover:bg-slate-50 transition-colors"
            disabled={appliedPolicies.size === 0}
          >
            Reset
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card>
          <div className="flex items-center gap-2 text-slate-500 text-xs font-semibold uppercase tracking-wide">
            <TrendingDown size={14} />
            Potential Monthly Savings
          </div>
          <div className="text-3xl font-bold text-emerald-600 mt-2">{formatCost(estimatedTotalSavings)}</div>
          <div className="text-xs text-slate-400 mt-1">Across {recommendations.length} recommendations</div>
        </Card>

        <Card>
          <div className="flex items-center gap-2 text-slate-500 text-xs font-semibold uppercase tracking-wide">
            <Zap size={14} />
            Simulated Savings Applied
          </div>
          <div className="text-3xl font-bold text-indigo-700 mt-2">{formatCost(appliedSavings)}</div>
          <div className="text-xs text-slate-400 mt-1">
            {appliedPolicies.size} of {recommendations.length} policies selected
          </div>
        </Card>

        <Card>
          <div className="flex items-center gap-2 text-slate-500 text-xs font-semibold uppercase tracking-wide">
            <Lightbulb size={14} />
            Before / After Cost
          </div>
          <div className="mt-2">
            <div className="text-xs text-slate-500">Before: <span className="font-semibold text-slate-900">{formatCost(totalCost)}</span></div>
            <div className="text-xs text-slate-500 mt-1">After: <span className="font-semibold text-emerald-700">{formatCost(projectedSpend)}</span></div>
          </div>
          <div className="text-xs text-slate-400 mt-2">
            {formatTokens(totalInputTokens + totalOutputTokens)} across {formatNumber(usageEvents.length)} events
          </div>
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-4">
          {recommendations.map(rec => (
            <Card key={rec.id} className={appliedPolicies.has(rec.id) ? 'border-emerald-200 bg-emerald-50/20' : ''}>
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h2 className="text-lg font-semibold text-slate-900">{rec.title}</h2>
                  <p className="text-sm text-slate-600 mt-1">{rec.description}</p>
                </div>
                <div className="text-right shrink-0">
                  <div className="text-xs text-slate-400">Est. savings / month</div>
                  <div className="text-xl font-bold text-emerald-600">{formatCost(rec.estimatedMonthlySavingsUsd)}</div>
                </div>
              </div>

              <div className="flex items-center gap-2 mt-4">
                <Badge className="bg-emerald-50 text-emerald-700">Impact: {rec.impact}</Badge>
                <Badge className="bg-indigo-50 text-indigo-700">Confidence: {rec.confidence}</Badge>
                {appliedPolicies.has(rec.id) && (
                  <Badge className="bg-emerald-100 text-emerald-800">Applied in simulation</Badge>
                )}
              </div>

              <ul className="mt-4 space-y-2 text-sm text-slate-600">
                {rec.details.map(detail => (
                  <li key={detail} className="flex items-start gap-2">
                    <span className="mt-1 text-indigo-500">•</span>
                    <span>{detail}</span>
                  </li>
                ))}
              </ul>

              <div className="mt-4 border-t border-slate-100 pt-4 flex items-center justify-between gap-4">
                <div className="text-xs text-slate-500">
                  If applied alone: {formatCost(totalCost)} {'->'} {formatCost(Math.max(0, totalCost - rec.estimatedMonthlySavingsUsd))}
                </div>
                <button
                  type="button"
                  onClick={() => togglePolicy(rec.id)}
                  className={
                    appliedPolicies.has(rec.id)
                      ? 'px-3 py-1.5 text-xs font-medium rounded-lg border border-emerald-200 bg-emerald-100 text-emerald-800 hover:bg-emerald-200 transition-colors'
                      : 'px-3 py-1.5 text-xs font-medium rounded-lg border border-indigo-200 bg-indigo-50 text-indigo-700 hover:bg-indigo-100 transition-colors'
                  }
                >
                  {appliedPolicies.has(rec.id) ? 'Remove policy simulation' : 'Apply policy simulation'}
                </button>
              </div>
            </Card>
          ))}
        </div>

        <Card>
          <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wide">Top Cost Drivers</h2>
          <div className="mt-4 space-y-3">
            {topModelsByCost.map((item, idx) => (
              <div key={item.modelId} className="rounded-xl border border-slate-100 p-3">
                <div className="flex items-center justify-between">
                  <div className="text-sm font-medium text-slate-800">#{idx + 1} {item.modelName}</div>
                  <div className="text-sm font-semibold text-slate-900">{formatCost(item.cost)}</div>
                </div>
                <div className="text-xs text-slate-500 mt-1">
                  {formatTokens(item.tokens)} tokens across {formatNumber(item.events)} events
                </div>
              </div>
            ))}
          </div>

          <div className="mt-6 p-3 rounded-xl bg-indigo-50 text-indigo-800 text-sm">
            <div className="font-semibold flex items-center gap-2">
              <ArrowRightLeft size={14} />
              Quick win
            </div>
            <p className="mt-1 text-indigo-700">
              Start with routing and token caps first. They typically deliver savings fastest without large architecture changes.
            </p>
          </div>
        </Card>
      </div>
    </div>
  );
}
