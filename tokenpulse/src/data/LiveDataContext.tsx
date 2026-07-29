import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useAuth } from '@/auth/AuthContext';
import { createAlert, fetchLiveDataset, removeAlert, updateAlert } from './liveApi';
import type { Alert, TokenPulseDataset } from './types';

export const LIVE_WINDOW_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '15', label: 'Last 15 minutes' },
  { value: '60', label: 'Last 1 hour' },
  { value: '360', label: 'Last 6 hours' },
  { value: '1440', label: 'Last 24 hours' },
  { value: '10080', label: 'Last 7 days' },
  { value: '43200', label: 'Last 30 days' },
  { value: '129600', label: 'Last 90 days' },
  { value: '525600', label: 'Last 1 year' },
  { value: 'forever', label: 'Forever' },
];


interface LiveDataState {
  data: TokenPulseDataset | null;
  isLoading: boolean;
  error: string | null;
  selectedWindow: string;
  setSelectedWindow: (value: string) => void;
  refresh: () => Promise<void>;
  saveAlert: (input: Alert | Omit<Alert, 'id'>) => Promise<void>;
  deleteAlert: (alertId: string) => Promise<void>;
}

const LiveDataContext = createContext<LiveDataState | undefined>(undefined);

export function LiveDataProvider({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, selectedSubscriptionId, refreshAuth } = useAuth();
  const [data, setData] = useState<TokenPulseDataset | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedWindow, setSelectedWindow] = useState('43200');

  const refresh = useCallback(async () => {
    if (!isAuthenticated) {
      await refreshAuth().catch(() => {});
      setData(null);
      setError('Sign in with Microsoft Entra to load live tenant data.');
      return;
    }

    setIsLoading(true);
    setError(null);
    try {
      const next = await fetchLiveDataset(selectedSubscriptionId, undefined, selectedWindow);
      setData(next);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load live tenant data.');
      setData(null);
    } finally {
      setIsLoading(false);
    }
  }, [isAuthenticated, refreshAuth, selectedSubscriptionId, selectedWindow]);

  useEffect(() => {
    if (isAuthenticated) {
      void refresh();
    }
  }, [isAuthenticated, selectedSubscriptionId, selectedWindow, refresh]);

  const saveAlert = useCallback(async (input: Alert | Omit<Alert, 'id'>) => {
    if (!isAuthenticated) throw new Error('Sign in with Microsoft Entra to manage alerts.');
    if ('id' in input) {
      await updateAlert(input, selectedSubscriptionId);
    } else {
      await createAlert(input, selectedSubscriptionId);
    }
    await refresh();
  }, [isAuthenticated, selectedSubscriptionId, refresh]);

  const deleteAlert = useCallback(async (alertId: string) => {
    if (!isAuthenticated) throw new Error('Sign in with Microsoft Entra to manage alerts.');
    await removeAlert(alertId, selectedSubscriptionId);
    await refresh();
  }, [isAuthenticated, selectedSubscriptionId, refresh]);

  const value = useMemo<LiveDataState>(() => ({
    data,
    isLoading,
    error,
    selectedWindow,
    setSelectedWindow,
    refresh,
    saveAlert,
    deleteAlert,
  }), [data, isLoading, error, selectedWindow, refresh, saveAlert, deleteAlert]);

  return <LiveDataContext.Provider value={value}>{children}</LiveDataContext.Provider>;
}

export function useLiveData() {
  const context = useContext(LiveDataContext);
  if (!context) {
    throw new Error('useLiveData must be used within LiveDataProvider');
  }
  return context;
}
