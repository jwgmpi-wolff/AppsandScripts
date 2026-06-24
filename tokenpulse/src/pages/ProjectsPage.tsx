import { useState, useMemo } from 'react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Sparkline } from '@/components/charts/Sparkline';
import { DonutChart } from '@/components/charts/DonutChart';
import { getCostByProject, getSparklineForProject, filterEvents, sumCost, formatCost, formatTokens, formatNumber } from '@/data/queries';
import { useLiveData } from '@/data/LiveDataContext';
import { ArrowLeft } from 'lucide-react';

export function ProjectsPage() {
  const { data } = useLiveData();
  if (!data) return null;
  const live = data;

  function getModelName(id: string) { return live.models.find(m => m.id === id)?.name ?? id; }
  function getProviderName(id: string) { return live.providers.find(p => p.id === id)?.name ?? id; }
  function getModelProviderId(id: string) { return live.models.find(m => m.id === id)?.providerId ?? ''; }
  function getProviderColor(id: string) { return live.providers.find(p => p.id === id)?.color ?? '#94a3b8'; }

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const now = useMemo(() => new Date(), []);
  const projectCosts = useMemo(() => getCostByProject(live), [live]);

  const selectedProject = useMemo(() =>
    projectCosts.find(p => p.project.id === selectedId),
  [projectCosts, selectedId]);

  const perModelData = useMemo(() => {
    if (!selectedId) return [];
    const evts = filterEvents(live.usageEvents, live.models, { projectId: selectedId });
    const byModel: Record<string, { cost: number; tokens: number; count: number }> = {};
    evts.forEach(e => {
      byModel[e.modelId] = byModel[e.modelId] ?? { cost: 0, tokens: 0, count: 0 };
      byModel[e.modelId].cost += e.cost;
      byModel[e.modelId].tokens += e.inputTokens + e.outputTokens;
      byModel[e.modelId].count++;
    });
    return Object.entries(byModel)
      .map(([modelId, v]) => ({ modelId, ...v }))
      .sort((a, b) => b.cost - a.cost);
  }, [selectedId, live]);

  const donutData = useMemo(() =>
    perModelData.map(d => ({
      label: getModelName(d.modelId),
      value: d.cost,
      color: getProviderColor(getModelProviderId(d.modelId)),
    })),
  [perModelData]);

  if (selectedId && selectedProject) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <button
            onClick={() => setSelectedId(null)}
            className="flex items-center gap-2 text-sm text-slate-500 hover:text-indigo-600 transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500 rounded-lg px-2 py-1"
          >
            <ArrowLeft size={16} /> Back to Projects
          </button>
        </div>

        <div className="flex items-center gap-3">
          <div className="w-4 h-4 rounded-full" style={{ backgroundColor: selectedProject.project.color }} />
          <h1 className="text-2xl font-bold text-slate-900">{selectedProject.project.name}</h1>
          <span className="text-slate-400 text-sm">{selectedProject.project.description}</span>
        </div>

        <div className="grid grid-cols-3 gap-4">
          <Card>
            <div className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-1">Total Cost</div>
            <div className="text-2xl font-bold text-slate-900">{formatCost(selectedProject.cost)}</div>
          </Card>
          <Card>
            <div className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-1">Total Tokens</div>
            <div className="text-2xl font-bold text-slate-900">{formatTokens(selectedProject.tokens)}</div>
          </Card>
          <Card>
            <div className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-1">Models Used</div>
            <div className="text-2xl font-bold text-slate-900">{perModelData.length}</div>
          </Card>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <Card className="lg:col-span-2">
            <div className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-4">Cost by Model</div>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100">
                  <th className="pb-2 text-left font-semibold text-slate-500">Model</th>
                  <th className="pb-2 text-left font-semibold text-slate-500">Provider</th>
                  <th className="pb-2 text-right font-semibold text-slate-500">Tokens</th>
                  <th className="pb-2 text-right font-semibold text-slate-500">Events</th>
                  <th className="pb-2 text-right font-semibold text-slate-500">Cost</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {perModelData.map(d => {
                  const pid = getModelProviderId(d.modelId);
                  return (
                    <tr key={d.modelId} className="hover:bg-slate-50">
                      <td className="py-2.5 font-medium text-slate-800">{getModelName(d.modelId)}</td>
                      <td className="py-2.5">
                        <span className="text-xs font-medium px-2 py-0.5 rounded-full" style={{ backgroundColor: `${getProviderColor(pid)}20`, color: getProviderColor(pid) }}>
                          {getProviderName(pid)}
                        </span>
                      </td>
                      <td className="py-2.5 text-right text-slate-600">{formatTokens(d.tokens)}</td>
                      <td className="py-2.5 text-right text-slate-500">{formatNumber(d.count)}</td>
                      <td className="py-2.5 text-right font-semibold text-slate-800">{formatCost(d.cost)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>

          <Card>
            <div className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-4">Cost Distribution</div>
            <DonutChart data={donutData} size={160} />
          </Card>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">Projects</h1>
        <p className="text-slate-500 mt-1">Token spend breakdown per project</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {projectCosts.map(({ project, cost, tokens }) => {
          const sparkData = getSparklineForProject(live, project.id, 14, now);
          const total30Cost = sumCost(filterEvents(live.usageEvents, live.models, {}));
          const pct = total30Cost > 0 ? (cost / total30Cost * 100).toFixed(1) : '0';
          const evtCount = filterEvents(live.usageEvents, live.models, { projectId: project.id }).length;
          return (
            <button
              key={project.id}
              onClick={() => setSelectedId(project.id)}
              className="text-left focus:outline-none focus:ring-2 focus:ring-indigo-500 rounded-2xl"
            >
              <Card className="hover:shadow-md hover:border-indigo-100 transition-all cursor-pointer group">
                <div className="flex items-start justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <div className="w-3 h-3 rounded-full" style={{ backgroundColor: project.color }} />
                    <span className="font-semibold text-slate-800 group-hover:text-indigo-700 transition-colors">
                      {project.name}
                    </span>
                  </div>
                  <Badge color={project.color}>{pct}%</Badge>
                </div>
                <p className="text-xs text-slate-400 mb-4">{project.description}</p>

                <div className="flex items-end justify-between mb-3">
                  <div>
                    <div className="text-2xl font-bold text-slate-900">{formatCost(cost)}</div>
                    <div className="text-xs text-slate-400">{formatTokens(tokens)} tokens · {formatNumber(evtCount)} events</div>
                  </div>
                  <Sparkline data={sparkData} color={project.color} width={90} height={36} />
                </div>
              </Card>
            </button>
          );
        })}
      </div>
    </div>
  );
}
