import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { createAlert, fetchLiveDataset, removeAlert, updateAlert } from './liveApi';
import type { Alert, TokenPulseDataset } from './types';

interface LiveDataState {
  data: TokenPulseDataset | null;
  isLoading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  saveAlert: (input: Alert | Omit<Alert, 'id'>) => Promise<void>;
  deleteAlert: (alertId: string) => Promise<void>;
}

const LiveDataContext = createContext<LiveDataState | undefined>(undefined);

export function LiveDataProvider({ children }: { children: React.ReactNode }) {
  const [data, setData] = useState<TokenPulseDataset | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const next = await fetchLiveDataset();
      setData(next);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load live tenant data.');
      setData(null);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const saveAlert = useCallback(async (input: Alert | Omit<Alert, 'id'>) => {
    if ('id' in input) {
      await updateAlert(input);
    } else {
      await createAlert(input);
    }
    await refresh();
  }, [refresh]);

  const deleteAlert = useCallback(async (alertId: string) => {
    await removeAlert(alertId);
    await refresh();
  }, [refresh]);

  const value = useMemo<LiveDataState>(() => ({
    data,
    isLoading,
    error,
    refresh,
    saveAlert,
    deleteAlert,
  }), [data, isLoading, error, refresh, saveAlert, deleteAlert]);

  return <LiveDataContext.Provider value={value}>{children}</LiveDataContext.Provider>;
}

export function useLiveData() {
  const context = useContext(LiveDataContext);
  if (!context) {
    throw new Error('useLiveData must be used within LiveDataProvider');
  }
  return context;
}
