import { useMemo } from 'react';
import { Card } from '@/components/ui/Card';
import { HorizBarChart } from '@/components/charts/HorizBarChart';
import { getCostByModel, formatCost, formatTokens, formatNumber } from '@/data/queries';
import { useLiveData } from '@/data/LiveDataContext';

export function ModelsPage() {
  const { data } = useLiveData();
  if (!data) return null;
  const live = data;

  function getProviderColor(id: string) { return live.providers.find(p => p.id === id)?.color ?? '#94a3b8'; }
  function getProviderName(id: string) { return live.providers.find(p => p.id === id)?.name ?? id; }

  const modelData = useMemo(() => getCostByModel(live), [live]);

  const chartData = useMemo(() =>
    modelData.map(d => ({
      label: d.model.name,
      value: d.tokens,
      color: getProviderColor(d.model.providerId),
    })),
  [modelData]);

  const chartDataEvents = useMemo(() =>
    modelData.map(d => ({
      label: d.model.name,
      value: d.events,
      color: getProviderColor(d.model.providerId),
    })),
  [modelData]);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">Models</h1>
        <p className="text-slate-500 mt-1">Compare models by usage, cost, and efficiency</p>
      </div>

      {/* Models Table */}
      <Card className="p-0 overflow-hidden">
        <div className="px-6 py-4 border-b border-slate-100">
          <h2 className="font-semibold text-slate-700">Model Overview</h2>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-100">
                <th className="px-4 py-3 text-left font-semibold text-slate-600">Model</th>
                <th className="px-4 py-3 text-left font-semibold text-slate-600">Provider</th>
                <th className="px-4 py-3 text-right font-semibold text-slate-600">Input $/1K</th>
                <th className="px-4 py-3 text-right font-semibold text-slate-600">Output $/1K</th>
                <th className="px-4 py-3 text-right font-semibold text-slate-600">Total Tokens</th>
                <th className="px-4 py-3 text-right font-semibold text-slate-600">Events</th>
                <th className="px-4 py-3 text-right font-semibold text-slate-600">Total Cost</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {modelData.map(({ model, cost, tokens, events }) => {
                const pColor = getProviderColor(model.providerId);
                return (
                  <tr key={model.id} className="hover:bg-slate-50/70 transition-colors">
                    <td className="px-4 py-3 font-medium text-slate-900">{model.name}</td>
                    <td className="px-4 py-3">
                      <span
                        className="text-xs font-medium px-2.5 py-0.5 rounded-full"
                        style={{ backgroundColor: `${pColor}18`, color: pColor }}
                      >
                        {getProviderName(model.providerId)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-slate-600 font-mono text-xs">
                      ${model.inputPricePer1k.toFixed(5)}
                    </td>
                    <td className="px-4 py-3 text-right text-slate-600 font-mono text-xs">
                      ${model.outputPricePer1k.toFixed(5)}
                    </td>
                    <td className="px-4 py-3 text-right text-slate-600">{formatTokens(tokens)}</td>
                    <td className="px-4 py-3 text-right text-slate-500">{formatNumber(events)}</td>
                    <td className="px-4 py-3 text-right font-semibold text-slate-900">{formatCost(cost)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Card>

      {/* Bar Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <div className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-4">Tokens Used by Model</div>
          <HorizBarChart
            data={chartData}
            formatValue={v => formatTokens(v)}
          />
        </Card>
        <Card>
          <div className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-4">Events by Model</div>
          <HorizBarChart
            data={chartDataEvents}
            formatValue={v => formatNumber(v)}
          />
        </Card>
      </div>
    </div>
  );
}
