import { useMemo, useEffect, useState } from 'react';
import { Card, CardHeader, CardTitle, CardValue, CardSubtitle } from '@/components/ui/Card';
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
import { usageEvents, models } from '@/data/sampleData';
import { TrendingUp, DollarSign, FolderOpen, Cpu } from 'lucide-react';

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
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const timer = setTimeout(() => setLoading(false), 700);
    return () => clearTimeout(timer);
  }, []);

  const today = useMemo(() => getEventsInWindow(1), []);
  const week = useMemo(() => getEventsInWindow(7), []);
  const month = useMemo(() => getEventsInWindow(30), []);
  const dailyBuckets = useMemo(() => getDailyBuckets(usageEvents, 30), []);
  const projectCosts = useMemo(() => getCostByProject(), []);

  const topModel = useMemo(() => {
    const counts: Record<string, number> = {};
    month.forEach(e => { counts[e.modelId] = (counts[e.modelId] ?? 0) + 1; });
    const topId = Object.entries(counts).sort((a, b) => b[1] - a[1])[0]?.[0];
    return models.find(m => m.id === topId)?.name ?? '—';
  }, [month]);

  const donutData = useMemo(() =>
    projectCosts.map(({ project, cost }) => ({
      label: project.name,
      value: cost,
      color: project.color,
    })),
  [projectCosts]);

  const activeProjects = useMemo(() =>
    new Set(month.map(e => e.projectId)).size,
  [month]);

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
        <ProviderStackedBar data={dailyBuckets} height={220} />
      </Card>
    </div>
  );
}
