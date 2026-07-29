import { useMemo, useEffect, useState } from 'react';
import { Card, CardHeader, CardTitle, CardValue, CardSubtitle } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { TokenLineChart } from '@/components/charts/LineChart';
import { ProviderStackedBar } from '@/components/charts/StackedBarChart';
import { DonutChart } from '@/components/charts/DonutChart';
import {
  getDailyBuckets,
  getEventsInWindow,
  sumCost,
  sumTokens,
  getCostByProject,
  formatCost,
  formatTokens,
  formatNumber,
} from '@/data/queries';
import { useLiveData } from '@/data/LiveDataContext';
import { TrendingUp, DollarSign, FolderOpen, Cpu } from 'lucide-react';

const TAXONOMY_LABELS: Record<string, string> = {
  inputTokens: 'Input Tokens',
  outputTokens: 'Output Tokens',
  totalTokens: 'Total Tokens',
  cachedInputTokens: 'Cached Input Tokens',
  cachedTokens: 'Cached Tokens',
  audioInputTokens: 'Audio Input Tokens',
  audioOutputTokens: 'Audio Output Tokens',
  embeddingTokens: 'Embedding Tokens',
  trainingTokens: 'Training Tokens',
  validationTokens: 'Validation Tokens',
  cacheReadTokens: 'Cache Read Tokens',
  cacheWriteTokens: 'Cache Write Tokens',
  reasoningTokens: 'Reasoning Tokens',
  textTokens: 'Text Tokens',
  imageTokens: 'Image Tokens',
  tpm: 'TPM',
  rpm: 'RPM',
  ptuUtilization: 'PTU Utilization',
  otherTokenMetrics: 'Other Token Metrics',
};

function formatMetricValue(unit: string, value: number): string {
  if (unit === 'percent') return `${value.toFixed(2)}%`;
  if (unit === 'perMinute') return `${formatNumber(Math.round(value))} / min`;
  return `${formatTokens(Math.round(value))}`;
}

function KpiCard({
  title,
  value,
  subtitle,
  icon: Icon,
  iconColor,
}: {
  title: string;
  value: string;
  subtitle: string;
  icon: React.ComponentType<{ size?: number; className?: string }>;
  iconColor: string;
}) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between mb-2">
        <CardTitle>{title}</CardTitle>
        <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: `${iconColor}18` }}>
          <span style={{ color: iconColor }}>
            <Icon size={18} className="opacity-90" />
          </span>
        </div>
      </CardHeader>
      <CardValue>{value}</CardValue>
      <CardSubtitle>{subtitle}</CardSubtitle>
    </Card>
  );
}

export function DashboardPage() {
  const { data } = useLiveData();
  if (!data) return null;
  const live = data;

  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const timer = setTimeout(() => setLoading(false), 700);
    return () => clearTimeout(timer);
  }, []);

  const now = useMemo(() => new Date(), []);
  const today = useMemo(() => getEventsInWindow(live.usageEvents, 1, now), [live.usageEvents, now]);
  const week = useMemo(() => getEventsInWindow(live.usageEvents, 7, now), [live.usageEvents, now]);
  const month = useMemo(() => getEventsInWindow(live.usageEvents, 30, now), [live.usageEvents, now]);
  const dailyBuckets = useMemo(() => getDailyBuckets(live.usageEvents, live.models, 30, now), [live.usageEvents, live.models, now]);
  const projectCosts = useMemo(() => getCostByProject(live), [live]);

  const topModel = useMemo(() => {
    const counts: Record<string, number> = {};
    month.forEach(e => { counts[e.modelId] = (counts[e.modelId] ?? 0) + 1; });
    const topId = Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0];
    return live.models.find(m => m.id === topId)?.name ?? '—';
  }, [month, live.models]);

  const totalCost = useMemo(() => projectCosts.reduce((s, p) => s + p.cost, 0), [projectCosts]);

  const donutData = useMemo(() =>
    projectCosts
      .filter(({ cost, tokens }) => (totalCost > 0 ? cost > 0 : tokens > 0))
      .map(({ project, cost, tokens }) => ({
        label: project.name,
        value: totalCost > 0 ? cost : tokens,
        color: project.color,
      })),
  [projectCosts, totalCost]);

  const activeProjects = useMemo(() =>
    new Set(month.map(e => e.projectId)).size,
  [month]);

  const topSources = useMemo(() => {
    const counts = new Map<string, number>();
    for (const event of month) {
      const key = event.source ?? 'Unknown source';
      counts.set(key, (counts.get(key) ?? 0) + event.inputTokens + event.outputTokens);
    }
    return Array.from(counts.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5);
  }, [month]);

  const topPurposes = useMemo(() => {
    const counts = new Map<string, number>();
    for (const event of month) {
      const key = event.purpose ?? event.operationName ?? 'General model usage';
      counts.set(key, (counts.get(key) ?? 0) + event.inputTokens + event.outputTokens);
    }
    return Array.from(counts.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5);
  }, [month]);

  const importantMetricOrder = useMemo(() => [
    'inputTokens',
    'outputTokens',
    'cachedInputTokens',
    'cachedTokens',
    'totalTokens',
    'tpm',
    'rpm',
    'ptuUtilization',
    'embeddingTokens',
    'trainingTokens',
    'validationTokens',
    'audioInputTokens',
    'audioOutputTokens',
    'textTokens',
    'imageTokens',
    'cacheReadTokens',
    'cacheWriteTokens',
    'reasoningTokens',
  ], []);

  const metricLabelByKey = useMemo(() => {
    const map = new Map<string, string>();
    Object.entries(TAXONOMY_LABELS).forEach(([key, label]) => map.set(key, label));
    for (const item of live.metricCategoryTotals ?? []) {
      map.set(item.key, item.label);
    }
    return map;
  }, [live.metricCategoryTotals]);

  const tokenMetricReport = useMemo(() => {
    const totals = live.metricCategoryTotals ?? [];
    const byKey = new Map(totals.map(item => [item.key, item]));

    const ordered = importantMetricOrder
      .map(key => byKey.get(key))
      .filter((item): item is NonNullable<typeof item> => Boolean(item));

    const extras = totals
      .filter(item => !importantMetricOrder.includes(item.key))
      .sort((a, b) => b.total - a.total);

    return [...ordered, ...extras].slice(0, 24);
  }, [importantMetricOrder, live.metricCategoryTotals]);

  const dailyMetricSeries = useMemo(() => {
    const days = 30;
    const end = new Date(now);
    const dates: string[] = [];
    for (let i = 0; i < days; i++) {
      const d = new Date(end);
      d.setDate(d.getDate() - (days - 1 - i));
      dates.push(d.toISOString().slice(0, 10));
    }

    const dailyMap = new Map<string, number>();
    for (const point of live.metricCategoryDaily ?? []) {
      dailyMap.set(`${point.date}|${point.key}`, (dailyMap.get(`${point.date}|${point.key}`) ?? 0) + point.total);
    }

    const buildSeries = (key: string) => dates.map(date => ({
      date,
      value: dailyMap.get(`${date}|${key}`) ?? 0,
    }));

    return {
      inputTokens: buildSeries('inputTokens'),
      outputTokens: buildSeries('outputTokens'),
      totalTokens: buildSeries('totalTokens'),
      cachedInputTokens: buildSeries('cachedInputTokens'),
      tpm: buildSeries('tpm'),
      rpm: buildSeries('rpm'),
      ptuUtilization: buildSeries('ptuUtilization'),
    };
  }, [live.metricCategoryDaily, now]);

  const resourceBreakdown = useMemo(() => {
    const rows = new Map<string, {
      resourceId: string;
      resourceName: string;
      resourceType: string;
      modelName: string;
      values: Record<string, { total: number; unit: string }>;
      score: number;
    }>();

    for (const item of live.metricCategoryByResource ?? []) {
      if (!rows.has(item.resourceId)) {
        rows.set(item.resourceId, {
          resourceId: item.resourceId,
          resourceName: item.resourceName,
          resourceType: item.resourceType,
          modelName: item.modelName,
          values: {},
          score: 0,
        });
      }
      const row = rows.get(item.resourceId)!;
      row.values[item.key] = { total: item.total, unit: item.unit };
      if (item.unit === 'tokens') row.score += item.total;
    }

    return Array.from(rows.values()).sort((a, b) => b.score - a.score);
  }, [live.metricCategoryByResource]);

  const modelBreakdown = useMemo(() => {
    const rows = new Map<string, {
      modelId: string;
      modelName: string;
      providerId: string;
      values: Record<string, { total: number; unit: string }>;
      score: number;
    }>();

    for (const item of live.metricCategoryByModel ?? []) {
      if (!rows.has(item.modelId)) {
        rows.set(item.modelId, {
          modelId: item.modelId,
          modelName: item.modelName,
          providerId: item.providerId,
          values: {},
          score: 0,
        });
      }
      const row = rows.get(item.modelId)!;
      row.values[item.key] = { total: item.total, unit: item.unit };
      if (item.unit === 'tokens') row.score += item.total;
    }

    return Array.from(rows.values()).sort((a, b) => b.score - a.score);
  }, [live.metricCategoryByModel]);

  const breakdownColumns = useMemo(() => {
    const present = new Set<string>();
    for (const item of live.metricCategoryByResource ?? []) present.add(item.key);
    for (const item of live.metricCategoryByModel ?? []) present.add(item.key);

    const ordered = importantMetricOrder.filter(key => present.has(key));
    const extras = Array.from(present).filter(key => !importantMetricOrder.includes(key));
    return [...ordered, ...extras].slice(0, 10);
  }, [importantMetricOrder, live.metricCategoryByModel, live.metricCategoryByResource]);

  const saveTextFile = (filename: string, content: string, mime: string) => {
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  };

  const exportMetricTotalsJson = () => {
    saveTextFile(
      'tokenpulse-metric-category-totals.json',
      JSON.stringify(live.metricCategoryTotals ?? [], null, 2),
      'application/json',
    );
  };

  const exportMetricTotalsCsv = () => {
    const rows = live.metricCategoryTotals ?? [];
    const csv = [
      'key,label,unit,total',
      ...rows.map(row => `${JSON.stringify(row.key)},${JSON.stringify(row.label)},${JSON.stringify(row.unit)},${row.total}`),
    ].join('\n');
    saveTextFile('tokenpulse-metric-category-totals.csv', csv, 'text/csv;charset=utf-8');
  };

  const exportDailyHistoryJson = () => {
    saveTextFile(
      'tokenpulse-metric-category-daily.json',
      JSON.stringify(live.metricCategoryDaily ?? [], null, 2),
      'application/json',
    );
  };

  const exportDailyHistoryCsv = () => {
    const rows = live.metricCategoryDaily ?? [];
    const csv = [
      'date,key,label,unit,total',
      ...rows.map(row => `${row.date},${JSON.stringify(row.key)},${JSON.stringify(row.label)},${JSON.stringify(row.unit)},${row.total}`),
    ].join('\n');
    saveTextFile('tokenpulse-metric-category-daily.csv', csv, 'text/csv;charset=utf-8');
  };

  if (loading) {
    return (
      <div className="space-y-8">
        <div>
          <div className="h-8 w-44 bg-slate-200 rounded-lg animate-pulse" />
          <div className="h-4 w-80 bg-slate-100 rounded mt-2 animate-pulse" />
        </div>

        <div className="grid grid-cols-2 lg:grid-cols-4 gap-6">
          {Array.from({ length: 4 }).map((_, idx) => (
            <Card key={idx}>
              <div className="h-4 w-24 bg-slate-200 rounded animate-pulse" />
              <div className="h-8 w-32 bg-slate-100 rounded mt-4 animate-pulse" />
              <div className="h-3 w-24 bg-slate-100 rounded mt-3 animate-pulse" />
            </Card>
          ))}
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <Card className="lg:col-span-2 h-[280px]">
            <div className="h-4 w-40 bg-slate-200 rounded animate-pulse" />
            <div className="h-[220px] mt-5 bg-slate-100 rounded-xl animate-pulse" />
          </Card>
          <Card className="h-[280px]">
            <div className="h-4 w-40 bg-slate-200 rounded animate-pulse" />
            <div className="h-[220px] mt-5 bg-slate-100 rounded-xl animate-pulse" />
          </Card>
        </div>

        <Card className="h-[280px]">
          <div className="h-4 w-44 bg-slate-200 rounded animate-pulse" />
          <div className="h-[220px] mt-5 bg-slate-100 rounded-xl animate-pulse" />
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">Dashboard</h1>
        <p className="text-slate-500 mt-1">Token usage overview across all projects and models</p>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-6">
        <KpiCard
          title="Tokens Used (30d)"
          value={`${formatTokens(sumTokens(month))} tokens used`}
          subtitle={`${formatTokens(sumTokens(week))} used this week · ${formatTokens(sumTokens(today))} used today`}
          icon={TrendingUp}
          iconColor="#6366f1"
        />
        <KpiCard
          title="Total Cost (30d)"
          value={formatCost(sumCost(month))}
          subtitle={`${formatCost(sumCost(week))} this week · ${formatCost(sumCost(today))} today`}
          icon={DollarSign}
          iconColor="#10b981"
        />
        <KpiCard
          title="Active Projects"
          value={activeProjects.toString()}
          subtitle={`${formatNumber(month.length)} events in 30 days`}
          icon={FolderOpen}
          iconColor="#f59e0b"
        />
        <KpiCard
          title="Top Model"
          value={topModel}
          subtitle="Most used in 30 days"
          icon={Cpu}
          iconColor="#ec4899"
        />
      </div>

      {/* Charts Row 1 */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>Daily Token Usage (30d)</CardTitle>
          </CardHeader>
          <TokenLineChart
            data={dailyBuckets.map(b => ({ date: b.date, tokens: b.tokens }))}
            height={220}
          />
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Cost Share by Project</CardTitle>
          </CardHeader>
          <DonutChart data={donutData} size={180} />
        </Card>
      </div>

      {/* Charts Row 2 */}
      <Card>
        <CardHeader>
          <CardTitle>Daily Cost by Provider (30d)</CardTitle>
        </CardHeader>
        <ProviderStackedBar data={dailyBuckets} providers={live.providers} height={220} />
      </Card>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Where Tokens Come From</CardTitle>
            <CardSubtitle>Top sources in last 30 days</CardSubtitle>
          </CardHeader>
          <div className="space-y-2">
            {topSources.length === 0 && (
              <p className="text-sm text-slate-500">No usage events found in this window.</p>
            )}
            {topSources.map(([label, tokens]) => (
              <div key={label} className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2">
                <span className="text-sm text-slate-700 truncate pr-3" title={label}>{label}</span>
                <span className="text-sm font-semibold text-slate-900">{formatTokens(tokens)}</span>
              </div>
            ))}
          </div>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>What Tokens Are Used For</CardTitle>
            <CardSubtitle>Top operations in last 30 days</CardSubtitle>
          </CardHeader>
          <div className="space-y-2">
            {topPurposes.length === 0 && (
              <p className="text-sm text-slate-500">No usage events found in this window.</p>
            )}
            {topPurposes.map(([label, tokens]) => (
              <div key={label} className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2">
                <span className="text-sm text-slate-700 truncate pr-3" title={label}>{label}</span>
                <span className="text-sm font-semibold text-slate-900">{formatTokens(tokens)}</span>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <Card>
        <CardHeader className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
          <div>
            <CardTitle>AI Token And Capacity Metrics (Live)</CardTitle>
            <CardSubtitle>Cross-service metric taxonomy for OpenAI, Foundry, and related Azure services</CardSubtitle>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" size="sm" onClick={exportMetricTotalsCsv}>Export Totals CSV</Button>
            <Button type="button" variant="outline" size="sm" onClick={exportMetricTotalsJson}>Export Totals JSON</Button>
            <Button type="button" variant="outline" size="sm" onClick={exportDailyHistoryCsv}>Export Daily CSV</Button>
            <Button type="button" variant="outline" size="sm" onClick={exportDailyHistoryJson}>Export Daily JSON</Button>
          </div>
        </CardHeader>
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          {tokenMetricReport.length === 0 && (
            <p className="text-sm text-slate-500">No token/capacity metrics were discovered for this subscription in the selected window.</p>
          )}
          {tokenMetricReport.map(metric => (
            <div key={metric.key} className="rounded-lg border border-slate-100 px-3 py-2">
              <p className="text-xs uppercase tracking-wide text-slate-500">{metric.label}</p>
              <p className="text-base font-semibold text-slate-900 mt-1">{formatMetricValue(metric.unit, metric.total)}</p>
            </div>
          ))}
        </div>
      </Card>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Token Category Trends (30d)</CardTitle>
            <CardSubtitle>Input, output, total, and cached input token trajectories</CardSubtitle>
          </CardHeader>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {['inputTokens', 'outputTokens', 'totalTokens', 'cachedInputTokens'].map((key) => (
              <div key={key} className="rounded-lg border border-slate-100 p-3">
                <p className="text-xs uppercase tracking-wide text-slate-500 mb-2">{metricLabelByKey.get(key) ?? key}</p>
                <TokenLineChart
                  data={(dailyMetricSeries[key as keyof typeof dailyMetricSeries] ?? []).map(point => ({ date: point.date, tokens: point.value }))}
                  height={140}
                />
              </div>
            ))}
          </div>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Capacity Trends (30d)</CardTitle>
            <CardSubtitle>TPM, RPM, and PTU utilization over time</CardSubtitle>
          </CardHeader>
          <div className="grid grid-cols-1 gap-4">
            {['tpm', 'rpm', 'ptuUtilization'].map((key) => (
              <div key={key} className="rounded-lg border border-slate-100 p-3">
                <p className="text-xs uppercase tracking-wide text-slate-500 mb-2">{metricLabelByKey.get(key) ?? key}</p>
                <TokenLineChart
                  data={(dailyMetricSeries[key as keyof typeof dailyMetricSeries] ?? []).map(point => ({ date: point.date, tokens: point.value }))}
                  height={140}
                />
              </div>
            ))}
          </div>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Per-Resource Taxonomy Breakdown</CardTitle>
          <CardSubtitle>Live token and capacity metric totals by Azure resource</CardSubtitle>
        </CardHeader>
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left">
                <th className="py-2 pr-4 font-semibold text-slate-600">Resource</th>
                <th className="py-2 pr-4 font-semibold text-slate-600">Type</th>
                <th className="py-2 pr-4 font-semibold text-slate-600">Model</th>
                {breakdownColumns.map((key) => (
                  <th key={key} className="py-2 pr-4 font-semibold text-slate-600 whitespace-nowrap">{metricLabelByKey.get(key) ?? key}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {resourceBreakdown.length === 0 && (
                <tr>
                  <td colSpan={3 + breakdownColumns.length} className="py-4 text-slate-500">No per-resource metric taxonomy rows found in this window.</td>
                </tr>
              )}
              {resourceBreakdown.slice(0, 25).map((row) => (
                <tr key={row.resourceId} className="border-b border-slate-100 align-top">
                  <td className="py-2 pr-4 text-slate-900 font-medium whitespace-nowrap">{row.resourceName}</td>
                  <td className="py-2 pr-4 text-slate-600 whitespace-nowrap">{row.resourceType}</td>
                  <td className="py-2 pr-4 text-slate-600 whitespace-nowrap">{row.modelName}</td>
                  {breakdownColumns.map((key) => {
                    const cell = row.values[key];
                    return (
                      <td key={key} className="py-2 pr-4 text-slate-700 whitespace-nowrap">
                        {cell ? formatMetricValue(cell.unit, cell.total) : '—'}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Per-Model Taxonomy Breakdown</CardTitle>
          <CardSubtitle>Live token and capacity metric totals by model endpoint</CardSubtitle>
        </CardHeader>
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left">
                <th className="py-2 pr-4 font-semibold text-slate-600">Model</th>
                <th className="py-2 pr-4 font-semibold text-slate-600">Provider</th>
                {breakdownColumns.map((key) => (
                  <th key={key} className="py-2 pr-4 font-semibold text-slate-600 whitespace-nowrap">{metricLabelByKey.get(key) ?? key}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {modelBreakdown.length === 0 && (
                <tr>
                  <td colSpan={2 + breakdownColumns.length} className="py-4 text-slate-500">No per-model metric taxonomy rows found in this window.</td>
                </tr>
              )}
              {modelBreakdown.slice(0, 25).map((row) => (
                <tr key={row.modelId} className="border-b border-slate-100 align-top">
                  <td className="py-2 pr-4 text-slate-900 font-medium whitespace-nowrap">{row.modelName}</td>
                  <td className="py-2 pr-4 text-slate-600 whitespace-nowrap">{row.providerId}</td>
                  {breakdownColumns.map((key) => {
                    const cell = row.values[key];
                    return (
                      <td key={key} className="py-2 pr-4 text-slate-700 whitespace-nowrap">
                        {cell ? formatMetricValue(cell.unit, cell.total) : '—'}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
