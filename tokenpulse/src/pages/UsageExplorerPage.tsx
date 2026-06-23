import { useState, useMemo } from 'react';
import { Card } from '@/components/ui/Card';
import { Select } from '@/components/ui/Select';
import { Badge } from '@/components/ui/Badge';
import { filterEvents, formatCost, formatNumber, sumCost } from '@/data/queries';
import { providers, projects, models } from '@/data/sampleData';
import type { UsageEvent } from '@/data/types';
import { ChevronUp, ChevronDown } from 'lucide-react';

type SortKey = 'timestamp' | 'inputTokens' | 'outputTokens' | 'cost';
type SortDir = 'asc' | 'desc';

const PAGE_SIZE = 50;

function getModelName(id: string) { return models.find(m => m.id === id)?.name ?? id; }
function getProjectName(id: string) { return projects.find(p => p.id === id)?.name ?? id; }
function getProjectColor(id: string) { return projects.find(p => p.id === id)?.color ?? '#94a3b8'; }
function getProviderColor(providerId: string) { return providers.find(p => p.id === providerId)?.color ?? '#94a3b8'; }
function getProviderName(providerId: string) { return providers.find(p => p.id === providerId)?.name ?? providerId; }
function getModelProviderId(modelId: string) { return models.find(m => m.id === modelId)?.providerId ?? ''; }

export function UsageExplorerPage() {
  const [providerFilter, setProviderFilter] = useState('');
  const [projectFilter, setProjectFilter] = useState('');
  const [dateRange, setDateRange] = useState('30');
  const [sortKey, setSortKey] = useState<SortKey>('timestamp');
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const [page, setPage] = useState(1);

  const cutoff = useMemo(() => {
    const d = new Date('2026-06-23T23:59:59Z');
    d.setDate(d.getDate() - parseInt(dateRange));
    return d;
  }, [dateRange]);

  const filtered = useMemo(() => {
    const evts = filterEvents({
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
  }, [providerFilter, projectFilter, cutoff, sortKey, sortDir]);

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
                ...providers.map(p => ({ value: p.id, label: p.name })),
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
                ...projects.map(p => ({ value: p.id, label: p.name })),
              ]}
            />
          </div>
          <div className="flex-1 min-w-[160px]">
            <Select
              label="Date Range"
              value={dateRange}
              onChange={e => { setDateRange(e.target.value); setPage(1); }}
              options={[
                { value: '1', label: 'Last 24 hours' },
                { value: '7', label: 'Last 7 days' },
                { value: '14', label: 'Last 14 days' },
                { value: '30', label: 'Last 30 days' },
              ]}
            />
          </div>
        </div>
      </Card>

      {/* Table */}
      <Card className="p-0 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 border-b border-slate-100">
                <th className="px-4 py-3 text-left">
                  <button className="flex items-center gap-1 font-semibold text-slate-600 hover:text-slate-900" onClick={() => toggleSort('timestamp')}>
                    Timestamp <SortIcon k="timestamp" />
                  </button>
                </th>
                <th className="px-4 py-3 text-left font-semibold text-slate-600">Project</th>
                <th className="px-4 py-3 text-left font-semibold text-slate-600">Model</th>
                <th className="px-4 py-3 text-right">
                  <button className="flex items-center gap-1 font-semibold text-slate-600 hover:text-slate-900 ml-auto" onClick={() => toggleSort('inputTokens')}>
                    Input <SortIcon k="inputTokens" />
                  </button>
                </th>
                <th className="px-4 py-3 text-right">
                  <button className="flex items-center gap-1 font-semibold text-slate-600 hover:text-slate-900 ml-auto" onClick={() => toggleSort('outputTokens')}>
                    Output <SortIcon k="outputTokens" />
                  </button>
                </th>
                <th className="px-4 py-3 text-right">
                  <button className="flex items-center gap-1 font-semibold text-slate-600 hover:text-slate-900 ml-auto" onClick={() => toggleSort('cost')}>
                    Cost <SortIcon k="cost" />
                  </button>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {/* Summary Row */}
              <tr className="bg-indigo-50/60 font-semibold">
                <td className="px-4 py-2 text-indigo-700">Totals ({formatNumber(filtered.length)} events)</td>
                <td className="px-4 py-2" />
                <td className="px-4 py-2" />
                <td className="px-4 py-2 text-right text-indigo-700">{formatNumber(filtered.reduce((s, e) => s + e.inputTokens, 0))}</td>
                <td className="px-4 py-2 text-right text-indigo-700">{formatNumber(filtered.reduce((s, e) => s + e.outputTokens, 0))}</td>
                <td className="px-4 py-2 text-right text-indigo-700">{formatCost(sumCost(filtered))}</td>
              </tr>
              {pageData.map(event => {
                const providerId = getModelProviderId(event.modelId);
                return (
                  <tr key={event.id} className="hover:bg-slate-50/70 transition-colors">
                    <td className="px-4 py-3 text-slate-500 whitespace-nowrap">
                      {event.timestamp.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                    </td>
                    <td className="px-4 py-3">
                      <Badge color={getProjectColor(event.projectId)}>{getProjectName(event.projectId)}</Badge>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-col">
                        <span className="text-slate-800 font-medium">{getModelName(event.modelId)}</span>
                        <span className="text-xs text-slate-400" style={{ color: getProviderColor(providerId) }}>{getProviderName(providerId)}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-right text-slate-600">{formatNumber(event.inputTokens)}</td>
                    <td className="px-4 py-3 text-right text-slate-600">{formatNumber(event.outputTokens)}</td>
                    <td className="px-4 py-3 text-right font-medium text-slate-800">{formatCost(event.cost)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

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
