import { useState, useMemo } from 'react';
import { Card } from '@/components/ui/Card';
import { Select } from '@/components/ui/Select';
import { Badge } from '@/components/ui/Badge';
import { filterEvents, formatCost, formatNumber, sumCost } from '@/data/queries';
import { useLiveData } from '@/data/LiveDataContext';
import type { UsageEvent } from '@/data/types';
import { ChevronUp, ChevronDown } from 'lucide-react';

type SortKey = 'timestamp' | 'inputTokens' | 'outputTokens' | 'cost';
type SortDir = 'asc' | 'desc';

const PAGE_SIZE = 50;

export function UsageExplorerPage() {
  const { data } = useLiveData();
  if (!data) return null;
  const live = data;

  function getModelName(id: string) { return live.models.find(m => m.id === id)?.name ?? id; }
  function getProjectName(id: string) { return live.projects.find(p => p.id === id)?.name ?? id; }
  function getProjectColor(id: string) { return live.projects.find(p => p.id === id)?.color ?? '#94a3b8'; }
  function getProviderColor(providerId: string) { return live.providers.find(p => p.id === providerId)?.color ?? '#94a3b8'; }
  function getProviderName(providerId: string) { return live.providers.find(p => p.id === providerId)?.name ?? providerId; }
  function getModelProviderId(modelId: string) { return live.models.find(m => m.id === modelId)?.providerId ?? ''; }

  const [providerFilter, setProviderFilter] = useState('');
  const [projectFilter, setProjectFilter] = useState('');
  const [dateRange, setDateRange] = useState('window');
  const [sortKey, setSortKey] = useState<SortKey>('timestamp');
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const [page, setPage] = useState(1);

  const cutoff = useMemo(() => {
    if (dateRange === 'window' || dateRange === 'forever') return undefined;
    const minutes = Number.parseInt(dateRange, 10);
    if (!Number.isFinite(minutes) || minutes <= 0) return undefined;
    return new Date(Date.now() - minutes * 60 * 1000);
  }, [dateRange]);

  const filtered = useMemo(() => {
    const evts = filterEvents(live.usageEvents, live.models, {
      providerId: providerFilter || undefined,
      projectId: projectFilter || undefined,
      startDate: cutoff,
    });
    return evts.sort((a: UsageEvent, b: UsageEvent) => {
      let diff = 0;
      if (sortKey === 'timestamp') diff = a.timestamp.getTime() - b.timestamp.getTime();
      else if (sortKey === 'inputTokens') diff = a.inputTokens - b.inputTokens;
      else if (sortKey === 'outputTokens') diff = a.outputTokens - b.outputTokens;
      else if (sortKey === 'cost') diff = a.cost - b.cost;
      return sortDir === 'asc' ? diff : -diff;
    });
  }, [providerFilter, projectFilter, cutoff, sortKey, sortDir, live]);

  const totalPages = Math.ceil(filtered.length / PAGE_SIZE);
  const pageData = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  function toggleSort(key: SortKey) {
    if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortKey(key); setSortDir('desc'); }
    setPage(1);
  }

  function SortIcon({ k }: { k: SortKey }) {
    if (sortKey !== k) return <ChevronDown size={12} className="text-slate-300" />;
    return sortDir === 'asc' ? <ChevronUp size={12} className="text-indigo-500" /> : <ChevronDown size={12} className="text-indigo-500" />;
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">Usage Explorer</h1>
        <p className="text-slate-500 mt-1">Browse and filter all usage events</p>
      </div>

      {/* Filters */}
      <Card className="py-4">
        <div className="flex flex-wrap gap-4 items-end">
          <div className="flex-1 min-w-[160px]">
            <Select
              label="Provider"
              value={providerFilter}
              onChange={e => { setProviderFilter(e.target.value); setPage(1); }}
              options={[
                { value: '', label: 'All Providers' },
                ...live.providers.map(p => ({ value: p.id, label: p.name })),
              ]}
            />
          </div>
          <div className="flex-1 min-w-[160px]">
            <Select
              label="Project"
              value={projectFilter}
              onChange={e => { setProjectFilter(e.target.value); setPage(1); }}
              options={[
                { value: '', label: 'All Projects' },
                ...live.projects.map(p => ({ value: p.id, label: p.name })),
              ]}
            />
          </div>
          <div className="flex-1 min-w-[160px]">
            <Select
              label="Date Range"
              value={dateRange}
              onChange={e => { setDateRange(e.target.value); setPage(1); }}
              options={[
                { value: 'window', label: 'All in selected live window' },
                { value: '15', label: 'Last 15 minutes' },
                { value: '60', label: 'Last 1 hour' },
                { value: '360', label: 'Last 6 hours' },
                { value: '1440', label: 'Last 24 hours' },
                { value: '10080', label: 'Last 7 days' },
                { value: '20160', label: 'Last 14 days' },
                { value: '43200', label: 'Last 30 days' },
                { value: '129600', label: 'Last 90 days' },
                { value: '525600', label: 'Last 1 year' },
                { value: 'forever', label: 'Forever' },
              ]}
            />
          </div>
        </div>
      </Card>

      {/* Table */}
      <Card className="p-0 overflow-hidden">
        <div className="overflow-auto max-h-[72vh]">
          <table className="w-full text-sm table-fixed">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-100">
                <th className="sticky top-0 z-40 px-3 py-3 text-left w-[140px] bg-slate-50 sticky-col-1">
                  <button className="flex items-center gap-1 font-semibold text-slate-600 hover:text-slate-900" onClick={() => toggleSort('timestamp')}>
                    Timestamp <SortIcon k="timestamp" />
                  </button>
                </th>
                <th className="sticky top-0 z-40 px-3 py-3 text-left font-semibold text-slate-600 w-[120px] bg-slate-50 sticky-col-2">Project</th>
                <th className="sticky top-0 z-40 px-3 py-3 text-left font-semibold text-slate-600 w-[180px] bg-slate-50 sticky-col-3 sticky-divider">Model</th>
                <th className="sticky top-0 z-30 px-3 py-3 text-right w-[120px] bg-slate-50">
                  <button className="flex items-center gap-1 font-semibold text-slate-600 hover:text-slate-900 ml-auto" onClick={() => toggleSort('inputTokens')}>
                    Input <SortIcon k="inputTokens" />
                  </button>
                </th>
                <th className="sticky top-0 z-30 px-3 py-3 text-right w-[120px] bg-slate-50">
                  <button className="flex items-center gap-1 font-semibold text-slate-600 hover:text-slate-900 ml-auto" onClick={() => toggleSort('outputTokens')}>
                    Output <SortIcon k="outputTokens" />
                  </button>
                </th>
                <th className="sticky top-0 z-30 px-3 py-3 text-right w-[100px] bg-slate-50">
                  <button className="flex items-center gap-1 font-semibold text-slate-600 hover:text-slate-900 ml-auto" onClick={() => toggleSort('cost')}>
                    Cost <SortIcon k="cost" />
                  </button>
                </th>
                <th className="sticky top-0 z-30 px-3 py-3 text-left font-semibold text-slate-600 bg-slate-50">From</th>
                <th className="sticky top-0 z-30 px-3 py-3 text-left font-semibold text-slate-600 bg-slate-50">Used For</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {/* Summary Row */}
              <tr className="bg-indigo-50/60 font-semibold">
                <td className="sticky left-0 z-20 px-3 py-2 text-indigo-700 bg-indigo-50">Totals ({formatNumber(filtered.length)} events)</td>
                <td className="sticky z-20 px-3 py-2 bg-indigo-50 sticky-col-2" />
                <td className="sticky z-20 px-3 py-2 bg-indigo-50 sticky-col-3 sticky-divider" />
                <td className="px-3 py-2 text-right text-indigo-700">{formatNumber(filtered.reduce((s, e) => s + e.inputTokens, 0))}</td>
                <td className="px-3 py-2 text-right text-indigo-700">{formatNumber(filtered.reduce((s, e) => s + e.outputTokens, 0))}</td>
                <td className="px-3 py-2 text-right text-indigo-700">{formatCost(sumCost(filtered))}</td>
                <td className="px-3 py-2" />
                <td className="px-3 py-2" />
              </tr>
              {pageData.map(event => {
                const providerId = getModelProviderId(event.modelId);
                const fallbackSource = `${getProviderName(providerId)} · ${getModelName(event.modelId)}`;
                const fromLabel = event.source ?? fallbackSource;
                const usedForLabel = event.purpose ?? event.operationName ?? 'Model inference / tenant operation';
                return (
                  <tr key={event.id} className="group hover:bg-slate-50/70 transition-colors align-top">
                    <td className="sticky left-0 z-10 px-3 py-3 text-slate-500 whitespace-nowrap bg-white group-hover:bg-slate-50">
                      {event.timestamp.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                    </td>
                    <td className="sticky z-10 px-3 py-3 bg-white group-hover:bg-slate-50 sticky-col-2">
                      <Badge color={getProjectColor(event.projectId)}>{getProjectName(event.projectId)}</Badge>
                    </td>
                    <td className="sticky z-10 px-3 py-3 bg-white group-hover:bg-slate-50 sticky-col-3 sticky-divider">
                      <div className="flex flex-col gap-0.5">
                        <span className="text-slate-800 font-medium whitespace-normal break-words leading-5">{getModelName(event.modelId)}</span>
                        <span className="text-xs text-slate-400 whitespace-normal break-words leading-4" style={{ color: getProviderColor(providerId) }}>{getProviderName(providerId)}</span>
                      </div>
                    </td>
                    <td className="px-3 py-3 text-right text-slate-600 whitespace-nowrap">{formatNumber(event.inputTokens)}</td>
                    <td className="px-3 py-3 text-right text-slate-600 whitespace-nowrap">{formatNumber(event.outputTokens)}</td>
                    <td className="px-3 py-3 text-right font-medium text-slate-800 whitespace-nowrap">{formatCost(event.cost)}</td>
                    <td className="px-3 py-3 text-slate-600">
                      <div className="whitespace-normal break-words leading-5">{fromLabel}</div>
                    </td>
                    <td className="px-3 py-3 text-slate-600">
                      <div className="whitespace-normal break-words leading-5">{usedForLabel}</div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        <style>{`
          .sticky-col-1 { left: 0; }
          .sticky-col-2 { left: 140px; }
          .sticky-col-3 { left: 260px; }
          .sticky-divider {
            box-shadow: 6px 0 8px -8px rgba(15, 23, 42, 0.25);
          }
        `}</style>

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-slate-100">
            <span className="text-xs text-slate-400">
              Showing {((page - 1) * PAGE_SIZE) + 1}–{Math.min(page * PAGE_SIZE, filtered.length)} of {formatNumber(filtered.length)}
            </span>
            <div className="flex gap-2">
              <button
                className="px-3 py-1.5 text-xs rounded-lg border border-slate-200 hover:bg-slate-50 disabled:opacity-40"
                onClick={() => setPage(p => Math.max(1, p - 1))}
                disabled={page === 1}
              >
                Previous
              </button>
              <button
                className="px-3 py-1.5 text-xs rounded-lg border border-slate-200 hover:bg-slate-50 disabled:opacity-40"
                onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                disabled={page === totalPages}
              >
                Next
              </button>
            </div>
          </div>
        )}
      </Card>
    </div>
  );
}
