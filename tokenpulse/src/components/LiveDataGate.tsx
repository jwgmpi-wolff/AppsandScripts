import { Button } from '@/components/ui/Button';
import { useLiveData } from '@/data/LiveDataContext';

export function LiveDataGate({ children }: { children: React.ReactNode }) {
  const { data, isLoading, error, refresh } = useLiveData();

  if (isLoading) {
    return (
      <div className="min-h-[40vh] flex items-center justify-center">
        <div className="text-center">
          <p className="text-slate-500">Loading live tenant data...</p>
        </div>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="min-h-[40vh] flex items-center justify-center px-4">
        <div className="max-w-xl text-center space-y-4">
          <h2 className="text-xl font-semibold text-slate-900">Live data unavailable</h2>
          <p className="text-slate-600">
            TokenPulse is configured to use live tenant/resource data only. No demo fallback is enabled.
          </p>
          {error ? <p className="text-sm text-red-600">{error}</p> : null}
          <Button onClick={() => void refresh()}>Retry</Button>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
