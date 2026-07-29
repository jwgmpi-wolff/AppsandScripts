import { useState, useMemo } from 'react';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { ProgressBar } from '@/components/ui/ProgressBar';
import { Input } from '@/components/ui/Input';
import { Select } from '@/components/ui/Select';
import { Badge } from '@/components/ui/Badge';
import { getEventsInWindow, filterEvents, sumCost } from '@/data/queries';
import { useLiveData } from '@/data/LiveDataContext';
import type { Alert } from '@/data/types';
import { Plus, Pencil, Trash2, Bell, BellOff, AlertTriangle, CheckCircle } from 'lucide-react';

function useAlertSpend(alert: Alert): number {
  const { data } = useLiveData();

  return useMemo(() => {
    if (!data) return 0;

    const start = new Date();
    start.setDate(start.getDate() - alert.windowDays);

    const evts = alert.scope === 'global'
      ? getEventsInWindow(data.usageEvents, alert.windowDays)
      : filterEvents(data.usageEvents, data.models, { projectId: alert.projectId, startDate: start });

    return sumCost(evts);
  }, [alert.id, alert.scope, alert.projectId, alert.windowDays, data]);
}

const emptyAlert: Omit<Alert, 'id'> = {
  name: '',
  scope: 'global',
  projectId: undefined,
  thresholdUsd: 100,
  windowDays: 30,
  enabled: true,
};

function AlertCard({
  alert,
  onEdit,
  onDelete,
  onToggle,
  getProjectName,
  getProjectColor,
}: {
  alert: Alert;
  onEdit: () => void;
  onDelete: () => void;
  onToggle: () => void;
  getProjectName: (id: string) => string;
  getProjectColor: (id: string) => string;
}) {
  const spend = useAlertSpend(alert);
  const pct = Math.min(100, (spend / alert.thresholdUsd) * 100);
  const isOver = pct >= 100;
  const isNear = pct >= 70 && !isOver;

  return (
    <Card className={`transition-all ${!alert.enabled ? 'opacity-60' : ''} ${isOver ? 'border-red-200 bg-red-50/40' : isNear ? 'border-amber-200 bg-amber-50/30' : ''}`}>
      <div className="flex items-start justify-between mb-3">
        <div className="flex items-center gap-2">
          {isOver ? (
            <AlertTriangle size={16} className="text-red-500" />
          ) : isNear ? (
            <AlertTriangle size={16} className="text-amber-500" />
          ) : (
            <CheckCircle size={16} className="text-emerald-500" />
          )}
          <span className="font-semibold text-slate-800">{alert.name}</span>
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={onToggle}
            className="p-1.5 rounded-lg hover:bg-slate-100 transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500"
            aria-label={alert.enabled ? 'Disable alert' : 'Enable alert'}
          >
            {alert.enabled ? <Bell size={14} className="text-slate-500" /> : <BellOff size={14} className="text-slate-400" />}
          </button>
          <button
            onClick={onEdit}
            className="p-1.5 rounded-lg hover:bg-slate-100 transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500"
            aria-label="Edit alert"
          >
            <Pencil size={14} className="text-slate-500" />
          </button>
          <button
            onClick={onDelete}
            className="p-1.5 rounded-lg hover:bg-red-50 transition-colors focus:outline-none focus:ring-2 focus:ring-red-500"
            aria-label="Delete alert"
          >
            <Trash2 size={14} className="text-red-400" />
          </button>
        </div>
      </div>

      <div className="flex items-center gap-2 mb-3 text-sm text-slate-500">
        <Badge className={alert.scope === 'global' ? 'bg-slate-100 text-slate-600' : undefined} color={alert.scope === 'project' && alert.projectId ? getProjectColor(alert.projectId) : undefined}>
          {alert.scope === 'global' ? 'Global' : getProjectName(alert.projectId!)}
        </Badge>
        <span>·</span>
        <span>{alert.windowDays}d window</span>
        <span>·</span>
        <span>Threshold: <span className="font-semibold text-slate-700">${alert.thresholdUsd}</span></span>
      </div>

      <div className="space-y-1">
        <div className="flex justify-between text-xs">
          <span className={isOver ? 'text-red-600 font-medium' : isNear ? 'text-amber-600 font-medium' : 'text-slate-500'}>
            ${spend.toFixed(2)} spent
          </span>
          <span className="text-slate-400">{pct.toFixed(0)}% of ${alert.thresholdUsd}</span>
        </div>
        <ProgressBar value={pct} />
      </div>
    </Card>
  );
}

export function AlertsPage() {
  const { data, saveAlert, deleteAlert: removeAlert } = useLiveData();
  if (!data) return null;
  const live = data;

  function getProjectName(id: string) { return live.projects.find(p => p.id === id)?.name ?? id; }
  function getProjectColor(id: string) { return live.projects.find(p => p.id === id)?.color ?? '#94a3b8'; }

  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Alert | null>(null);
  const [form, setForm] = useState<Omit<Alert, 'id'>>(emptyAlert);
  const alertList = live.alerts;

  function openAdd() {
    setEditing(null);
    setForm(emptyAlert);
    setDialogOpen(true);
  }

  function openEdit(alert: Alert) {
    setEditing(alert);
    setForm({ name: alert.name, scope: alert.scope, projectId: alert.projectId, thresholdUsd: alert.thresholdUsd, windowDays: alert.windowDays, enabled: alert.enabled });
    setDialogOpen(true);
  }

  async function persistAlert() {
    if (!form.name.trim()) return;
    if (editing) {
      await saveAlert({ ...editing, ...form });
    } else {
      await saveAlert(form);
    }
    setDialogOpen(false);
  }

  async function deleteAlert(id: string) {
    await removeAlert(id);
  }

  async function toggleAlert(id: string) {
    const existing = alertList.find(a => a.id === id);
    if (!existing) return;
    await saveAlert({ ...existing, enabled: !existing.enabled });
  }

  const active = alertList.filter(a => a.enabled);
  const inactive = alertList.filter(a => !a.enabled);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Alerts</h1>
          <p className="text-slate-500 mt-1">Manage budget alerts and thresholds</p>
        </div>
        <Button onClick={openAdd}>
          <Plus size={16} /> New Alert
        </Button>
      </div>

      {active.length > 0 && (
        <div>
          <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-3">Active Alerts</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {active.map(a => (
              <AlertCard
                key={a.id}
                alert={a}
                onEdit={() => openEdit(a)}
                onDelete={() => void deleteAlert(a.id)}
                onToggle={() => void toggleAlert(a.id)}
                getProjectName={getProjectName}
                getProjectColor={getProjectColor}
              />
            ))}
          </div>
        </div>
      )}

      {inactive.length > 0 && (
        <div>
          <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-3">Disabled Alerts</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {inactive.map(a => (
              <AlertCard
                key={a.id}
                alert={a}
                onEdit={() => openEdit(a)}
                onDelete={() => void deleteAlert(a.id)}
                onToggle={() => void toggleAlert(a.id)}
                getProjectName={getProjectName}
                getProjectColor={getProjectColor}
              />
            ))}
          </div>
        </div>
      )}

      {alertList.length === 0 && (
        <div className="text-center py-16 text-slate-400">
          <Bell size={40} className="mx-auto mb-3 opacity-30" />
          <p>No alerts yet. Create one to track your budget.</p>
        </div>
      )}

      {/* Add/Edit Dialog */}
      <Dialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        title={editing ? 'Edit Alert' : 'New Alert'}
        description="Set a budget threshold to get notified when spending exceeds it."
      >
        <div className="space-y-4">
          <Input
            label="Alert Name"
            id="alert-name"
            value={form.name}
            onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
            placeholder="e.g. Monthly Global Budget"
          />
          <Select
            label="Scope"
            id="alert-scope"
            value={form.scope}
            onChange={e => setForm(f => ({ ...f, scope: e.target.value as 'global' | 'project', projectId: undefined }))}
            options={[
              { value: 'global', label: 'Global (all projects)' },
              { value: 'project', label: 'Per Project' },
            ]}
          />
          {form.scope === 'project' && (
            <Select
              label="Project"
              id="alert-project"
              value={form.projectId ?? ''}
              onChange={e => setForm(f => ({ ...f, projectId: e.target.value || undefined }))}
              options={[
                { value: '', label: 'Select a project…' },
                ...live.projects.map(p => ({ value: p.id, label: p.name })),
              ]}
            />
          )}
          <Input
            label="Threshold (USD)"
            id="alert-threshold"
            type="number"
            min="1"
            value={form.thresholdUsd}
            onChange={e => setForm(f => ({ ...f, thresholdUsd: parseFloat(e.target.value) || 0 }))}
          />
          <Select
            label="Window"
            id="alert-window"
            value={form.windowDays.toString()}
            onChange={e => setForm(f => ({ ...f, windowDays: parseInt(e.target.value, 10) }))}
            options={[
              { value: '1', label: 'Daily (1 day)' },
              { value: '7', label: 'Weekly (7 days)' },
              { value: '30', label: 'Monthly (30 days)' },
            ]}
          />

          <div className="flex justify-end gap-3 pt-2">
            <Button variant="outline" onClick={() => setDialogOpen(false)}>Cancel</Button>
            <Button onClick={() => void persistAlert()} disabled={!form.name.trim()}>
              {editing ? 'Save Changes' : 'Create Alert'}
            </Button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}
