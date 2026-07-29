import { Button } from '@/components/ui/Button';
import { useAuth } from '@/auth/AuthContext';
import { useLiveData } from '@/data/LiveDataContext';

export function LiveDataGate({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isInitializing: authLoading } = useAuth();
  const { data, isLoading, error, refresh } = useLiveData();
  const isAccessDenied = (error ?? '').toLowerCase().includes('access denied')
    || (error ?? '').toLowerCase().includes('authorizationfailed');

  if (authLoading) {
    return (
      <div className="min-h-[40vh] flex items-center justify-center">
        <div className="text-center">
          <p className="text-slate-500">Checking Microsoft Entra session...</p>
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return (
      <div className="min-h-[40vh] flex items-center justify-center px-4">
        <div className="max-w-xl text-center space-y-4">
          <h2 className="text-xl font-semibold text-slate-900">Azure authentication required</h2>
          <p className="text-slate-600">
            Sign in using the Azure Authentication panel above, then choose a subscription.
          </p>
        </div>
      </div>
    );
  }

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
          {isAccessDenied && (
            <p className="text-sm text-slate-600">
              Use the Azure Authentication panel above to switch subscription, or request Reader access on the selected subscription/resource group.
            </p>
          )}
          <div className="flex items-center justify-center gap-3">
            <Button onClick={() => void refresh()}>Retry</Button>
          </div>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
