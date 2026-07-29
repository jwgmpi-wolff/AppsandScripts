import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

export interface AzAccount {
  name: string;
  id: string;
  tenantId?: string | null;
  user?: { name: string };
}

export interface AzSubscription {
  id: string;
  name: string;
  tenantId?: string;
  isDefault?: boolean;
}

export interface AzTenant {
  id: string;
  name: string;
  defaultDomain?: string;
}

interface AuthPayload {
  authenticated: boolean;
  account?: AzAccount;
  tenants?: AzTenant[];
  subscriptions?: AzSubscription[];
  selectedTenantId?: string | null;
  selectedSubscriptionId?: string | null;
  error?: string;
}

interface AuthState {
  isAuthenticated: boolean;
  account: AzAccount | null;
  accessToken: string | null;
  tenants: AzTenant[];
  selectedTenantId: string | null;
  subscriptions: AzSubscription[];
  selectedSubscriptionId: string | null;
  isInitializing: boolean;
  isLoggingIn: boolean;
  isSelectingTenant: boolean;
  isSelectingSubscription: boolean;
  loginError: string | null;
  tenantError: string | null;
  subscriptionError: string | null;
  refreshAuth: () => Promise<void>;
  getValidAccessToken: () => Promise<string | null>;
  login: () => Promise<void>;
  selectTenant: (tenantId: string) => Promise<void>;
  selectSubscription: (subscriptionId: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

function clearAuthState(
  setAccount: (value: AzAccount | null) => void,
  setAccessToken: (value: string | null) => void,
  setTenants: (value: AzTenant[]) => void,
  setAllSubscriptions: (value: AzSubscription[]) => void,
  setSelectedTenantId: (value: string | null) => void,
  setSubscriptions: (value: AzSubscription[]) => void,
  setSelectedSubscriptionId: (value: string | null) => void,
) {
  setAccount(null);
  setAccessToken(null);
  setTenants([]);
  setAllSubscriptions([]);
  setSelectedTenantId(null);
  setSubscriptions([]);
  setSelectedSubscriptionId(null);
}

async function fetchAuthJson(path: string, init?: RequestInit): Promise<AuthPayload> {
  const res = await fetch(path, {
    ...init,
    cache: 'no-store',
  });

  const raw = await res.text();
  let payload: AuthPayload | null = null;
  if (raw) {
    try {
      payload = JSON.parse(raw) as AuthPayload;
    } catch {
      if (!res.ok) {
        throw new Error(raw);
      }
      throw new Error('Invalid auth response received from server.');
    }
  }

  if (!res.ok) {
    throw new Error(payload?.error ?? (raw || 'Failed to fetch live auth context.'));
  }

  if (!payload) {
    return {
      authenticated: false,
      account: undefined,
      tenants: [],
      subscriptions: [],
      selectedTenantId: null,
      selectedSubscriptionId: null,
      error: 'Empty auth response from server.',
    };
  }

  return payload;
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [account, setAccount] = useState<AzAccount | null>(null);
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [tenants, setTenants] = useState<AzTenant[]>([]);
  const [allSubscriptions, setAllSubscriptions] = useState<AzSubscription[]>([]);
  const [selectedTenantId, setSelectedTenantId] = useState<string | null>(null);
  const [subscriptions, setSubscriptions] = useState<AzSubscription[]>([]);
  const [selectedSubscriptionId, setSelectedSubscriptionId] = useState<string | null>(null);
  const [isInitializing, setIsInitializing] = useState(true);
  const [isLoggingIn, setIsLoggingIn] = useState(false);
  const [isSelectingTenant, setIsSelectingTenant] = useState(false);
  const [isSelectingSubscription, setIsSelectingSubscription] = useState(false);
  const [loginError, setLoginError] = useState<string | null>(null);
  const [tenantError, setTenantError] = useState<string | null>(null);
  const [subscriptionError, setSubscriptionError] = useState<string | null>(null);

  const getValidAccessToken = useCallback(async (): Promise<string | null> => accessToken, [accessToken]);

  const applyAuthPayload = useCallback((payload: AuthPayload) => {
    if (!payload.authenticated || !payload.account) {
      clearAuthState(setAccount, setAccessToken, setTenants, setAllSubscriptions, setSelectedTenantId, setSubscriptions, setSelectedSubscriptionId);
      return;
    }

    const normalizedAccount: AzAccount = {
      name: String(payload.account.name ?? 'Signed-in user'),
      id: String(payload.account.id ?? ''),
      tenantId: payload.account.tenantId ? String(payload.account.tenantId) : null,
      user: payload.account.user,
    };

    const normalizedTenants = Array.isArray(payload.tenants)
      ? payload.tenants.filter((tenant): tenant is AzTenant => Boolean(tenant && typeof tenant.id === 'string'))
      : [];

    const normalizedSubscriptions = Array.isArray(payload.subscriptions)
      ? payload.subscriptions
        .filter((sub): sub is AzSubscription => Boolean(sub && typeof sub.id === 'string'))
        .map(sub => ({
          ...sub,
          id: String(sub.id),
          name: String(sub.name ?? sub.id),
          tenantId: sub.tenantId ? String(sub.tenantId) : undefined,
        }))
      : [];

    setAccessToken('__server__');
    setAccount(normalizedAccount);
    setTenants(normalizedTenants);

    const allSubscriptions = normalizedSubscriptions;
    setAllSubscriptions(allSubscriptions);
    const tenantId = payload.selectedTenantId ?? normalizedAccount.tenantId ?? null;
    setSelectedTenantId(tenantId);

    setSubscriptions(allSubscriptions);

    const resolvedSub = payload.selectedSubscriptionId ?? allSubscriptions[0]?.id ?? null;
    setSelectedSubscriptionId(resolvedSub);
  }, []);

  const refreshAuth = useCallback(async () => {
    const payload = await fetchAuthJson('/api/auth/status');
    applyAuthPayload(payload);
  }, [applyAuthPayload]);

  useEffect(() => {
    refreshAuth().then(() => {
      setIsInitializing(false);
    }).catch((err) => {
      clearAuthState(setAccount, setAccessToken, setTenants, setAllSubscriptions, setSelectedTenantId, setSubscriptions, setSelectedSubscriptionId);
      setLoginError(err instanceof Error ? err.message : 'Authentication refresh failed. Please login again.');
      setIsInitializing(false);
    });
  }, [refreshAuth]);

  const login = useCallback(async () => {
    setIsLoggingIn(true);
    setLoginError(null);
    setTenantError(null);
    setSubscriptionError(null);
    try {
      const payload = await fetchAuthJson('/api/auth/login', { method: 'POST' });
      applyAuthPayload(payload);

      const subsRes = await fetch('/api/auth/subscriptions', { cache: 'no-store' });
      if (subsRes.ok) {
        const subsRaw = await subsRes.text();
        let subsPayload: { subscriptions?: AzSubscription[] } = {};
        if (subsRaw) {
          try {
            subsPayload = JSON.parse(subsRaw) as { subscriptions?: AzSubscription[] };
          } catch {
            throw new Error('Invalid subscriptions response received from server.');
          }
        }

        const availableSubscriptions = Array.isArray(subsPayload.subscriptions)
          ? subsPayload.subscriptions.filter((sub): sub is AzSubscription => Boolean(sub && typeof sub.id === 'string'))
          : [];
        setAllSubscriptions(availableSubscriptions);
        setSubscriptions(availableSubscriptions);

        const nextSelected = payload.selectedSubscriptionId ?? availableSubscriptions[0]?.id ?? null;
        if (nextSelected) {
          const switchedPayload = await fetchAuthJson('/api/auth/subscription', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ subscriptionId: nextSelected }),
          });
          applyAuthPayload(switchedPayload);
        }
      }
    } catch (err) {
      setLoginError(err instanceof Error ? err.message : 'Login failed');
    } finally {
      setIsLoggingIn(false);
    }
  }, [applyAuthPayload]);

  const selectTenant = useCallback(async (tenantId: string) => {
    setIsSelectingTenant(true);
    setTenantError(null);
    try {
      setSelectedTenantId(tenantId);
      const tenantSubscriptions = allSubscriptions.filter(sub => sub?.tenantId === tenantId);
      setSubscriptions(tenantSubscriptions);
      if (tenantSubscriptions.length === 0) {
        setSelectedSubscriptionId(null);
        setTenantError('No enabled subscriptions found for the selected tenant.');
      } else {
        setSelectedSubscriptionId(tenantSubscriptions[0].id);
      }
    } catch (err) {
      setTenantError(err instanceof Error ? err.message : 'Failed to select tenant.');
    } finally {
      setIsSelectingTenant(false);
    }
  }, [allSubscriptions]);

  const selectSubscription = useCallback(async (subscriptionId: string) => {
    setIsSelectingSubscription(true);
    setSubscriptionError(null);
    try {
      const payload = await fetchAuthJson('/api/auth/subscription', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subscriptionId }),
      });
      applyAuthPayload(payload);
    } catch (err) {
      setSubscriptionError(err instanceof Error ? err.message : 'Failed to select subscription.');
    } finally {
      setIsSelectingSubscription(false);
    }
  }, [applyAuthPayload]);

  const logout = useCallback(async () => {
    try { await fetch('/api/auth/logout', { method: 'POST', cache: 'no-store' }); } catch { /* ignore */ }

    clearAuthState(setAccount, setAccessToken, setTenants, setAllSubscriptions, setSelectedTenantId, setSubscriptions, setSelectedSubscriptionId);
    setLoginError(null);
    setTenantError(null);
    setSubscriptionError(null);
  }, []);

  const isAuthenticated = Boolean(account && selectedSubscriptionId);

  const value = useMemo<AuthState>(
    () => ({
      isAuthenticated,
      account,
      accessToken,
      tenants,
      selectedTenantId,
      subscriptions,
      selectedSubscriptionId,
      isInitializing,
      isLoggingIn,
      isSelectingTenant,
      isSelectingSubscription,
      loginError,
      tenantError,
      subscriptionError,
      refreshAuth,
      getValidAccessToken,
      login,
      selectTenant,
      selectSubscription,
      logout,
    }),
    [
      isAuthenticated,
      account,
      accessToken,
      tenants,
      selectedTenantId,
      subscriptions,
      selectedSubscriptionId,
      isInitializing,
      isLoggingIn,
      isSelectingTenant,
      isSelectingSubscription,
      loginError,
      tenantError,
      subscriptionError,
      refreshAuth,
      getValidAccessToken,
      login,
      selectTenant,
      selectSubscription,
      logout,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
