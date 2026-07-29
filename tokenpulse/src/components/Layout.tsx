import { NavLink, Outlet } from 'react-router-dom';
import { BarChart3, Zap, FolderOpen, Cpu, Bell, Activity, Lightbulb, RefreshCw } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuth } from '@/auth/AuthContext';
import { Select } from '@/components/ui/Select';
import { Button } from '@/components/ui/Button';
import { LIVE_WINDOW_OPTIONS, useLiveData } from '@/data/LiveDataContext';

const navItems = [
  { to: '/', label: 'Dashboard', icon: BarChart3, end: true },
  { to: '/usage', label: 'Usage Explorer', icon: Activity, end: false },
  { to: '/projects', label: 'Projects', icon: FolderOpen, end: false },
  { to: '/models', label: 'Models', icon: Cpu, end: false },
  { to: '/alerts', label: 'Alerts', icon: Bell, end: false },
  { to: '/recommendations', label: 'Recommendations', icon: Lightbulb, end: false },
];

export function Layout() {
  const {
    isAuthenticated,
    account,
    subscriptions,
    selectedSubscriptionId,
    isInitializing,
    isLoggingIn,
    isSelectingSubscription,
    loginError,
    subscriptionError,
    refreshAuth,
    login,
    selectSubscription,
    logout,
  } = useAuth();
  const { data, selectedWindow, setSelectedWindow } = useLiveData();

  const subscriptionOptions = subscriptions.map(sub => ({
    value: sub.id,
    label: sub.name,
  }));
  const selectedSubscriptionName = selectedSubscriptionId
    ? subscriptions.find(sub => sub.id === selectedSubscriptionId)?.name ?? 'Loading...'
    : 'No subscription selected';

  const accountLabel = account?.name ?? account?.user?.name ?? 'Signed in';
  const hasAuthError = Boolean(loginError || subscriptionError);
  const liveWindowSince = data?.selectedWindowSince ? new Date(data.selectedWindowSince) : null;
  const liveWindowUntil = data?.selectedWindowUntil ? new Date(data.selectedWindowUntil) : null;

  const formatWindowTimestamp = (value: Date | null) => {
    if (!value || Number.isNaN(value.getTime())) return '—';
    return value.toLocaleString('en-US', {
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
      timeZoneName: 'short',
    });
  };

  const liveWindowLabel = LIVE_WINDOW_OPTIONS.find(option => option.value === selectedWindow)?.label ?? selectedWindow;

  return (
    <div className="min-h-screen bg-gradient-to-br from-indigo-600 via-indigo-500 to-violet-500 flex flex-col">
      <section className="w-full px-4 pt-3 pb-3 md:px-[42px] md:pt-[14px] md:pb-[12px]">
        <div
          className={cn(
            'w-full bg-white rounded-[10px] border border-slate-200/80 shadow-[0_1px_2px_rgba(15,23,42,0.08)] px-6',
            hasAuthError ? 'py-[14px] min-h-[92px]' : 'h-[92px] py-[14px]',
          )}
        >
          <p className="text-[11px] tracking-[0.06em] uppercase font-semibold text-slate-500">Azure Authentication</p>
          <div className="mt-[10px] flex flex-wrap items-center justify-between gap-4 min-h-[40px]">
            <div className="flex items-center gap-4 min-w-0">
              <span
                className={cn(
                  'inline-flex items-center rounded-full px-3 py-1 text-[12px] leading-none font-medium',
                  isAuthenticated ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-800',
                )}
              >
                {isAuthenticated ? '✓ Authenticated' : 'Not authenticated'}
              </span>
              <p className="text-[16px] leading-5 text-slate-600 truncate max-w-[26rem]">
                Subscription: {isAuthenticated ? selectedSubscriptionName : 'Sign in to view subscriptions'}
              </p>
            </div>

            <div className="flex items-center gap-2 min-w-[22rem] justify-end flex-wrap">
              {isAuthenticated ? (
                <>
                  <span className="text-[15px] text-slate-700">Window:</span>
                  <div className="w-[190px] max-w-full">
                    <Select
                      id="window-select"
                      aria-label="Live data window"
                      options={LIVE_WINDOW_OPTIONS}
                      value={selectedWindow}
                      disabled={isInitializing || isSelectingSubscription}
                      onChange={(event) => {
                        setSelectedWindow(event.target.value);
                      }}
                      className="h-10 text-[14px]"
                    />
                  </div>
                  <span className="text-[15px] text-slate-700">Subscription:</span>
                  <div className="w-[436px] max-w-full">
                    <Select
                      id="subscription-select"
                      aria-label="Azure subscription"
                      options={subscriptionOptions.length > 0
                        ? subscriptionOptions
                        : [{ value: '', label: 'No subscriptions found' }]}
                      value={selectedSubscriptionId ?? ''}
                      disabled={isSelectingSubscription || subscriptionOptions.length === 0}
                      onChange={(event) => {
                        const nextId = event.target.value;
                        if (nextId && nextId !== selectedSubscriptionId) {
                          void selectSubscription(nextId);
                        }
                      }}
                      className="h-10 text-[15px]"
                    />
                  </div>
                  <Button
                    type="button"
                    variant="outline"
                    aria-label="Refresh authentication status"
                    title="Refresh authentication status"
                    className="h-10 w-10 p-0 border-0 bg-gradient-to-br from-indigo-500 to-violet-600 text-white hover:brightness-110"
                    onClick={() => void refreshAuth()}
                  >
                    <RefreshCw size={15} />
                  </Button>
                  <Button
                    type="button"
                    className="h-10 px-4 border-0 bg-gradient-to-br from-indigo-500 to-violet-600 text-white hover:brightness-110"
                    onClick={() => void logout()}
                  >
                    Logout
                  </Button>
                </>
              ) : (
                <Button
                  type="button"
                  className="h-10 px-4 border-0 bg-gradient-to-br from-indigo-500 to-violet-600 text-white hover:brightness-110"
                  onClick={() => void login()}
                  disabled={isInitializing || isLoggingIn}
                >
                  {isLoggingIn ? 'Logging in...' : 'Login'}
                </Button>
              )}
            </div>
          </div>
          {(loginError || subscriptionError) && (
            <p className="mt-2 text-xs text-red-600">{loginError ?? subscriptionError}</p>
          )}
          {isAuthenticated && data && (
            <div className="mt-2 rounded-md border border-indigo-100 bg-indigo-50/70 px-3 py-2 text-xs text-indigo-800">
              <span className="font-semibold">Current Live Window:</span> {liveWindowLabel}
              <span className="mx-2 text-indigo-300">|</span>
              <span className="font-medium">Since:</span> {formatWindowTimestamp(liveWindowSince)}
              <span className="mx-2 text-indigo-300">|</span>
              <span className="font-medium">Until:</span> {formatWindowTimestamp(liveWindowUntil)}
            </div>
          )}
        </div>
      </section>

      {/* Top Nav */}
      <header className="sticky top-0 z-20 bg-white/85 backdrop-blur-md border-b border-slate-200 shadow-sm">
        <div className="max-w-screen-2xl mx-auto px-6 h-16 flex items-center justify-between">
          {/* Brand */}
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center shadow-md">
              <Zap size={16} className="text-white" />
            </div>
            <span className="text-lg font-bold bg-gradient-to-r from-indigo-600 to-purple-600 bg-clip-text text-transparent">
              TokenPulse
            </span>
          </div>

          {/* Nav Items */}
          <nav className="flex items-center gap-1" aria-label="Main navigation">
            {navItems.map(({ to, label, icon: Icon, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) =>
                  cn(
                    'flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-medium transition-all duration-150',
                    'focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-1',
                    isActive
                      ? 'bg-indigo-50 text-indigo-700 shadow-sm'
                      : 'text-slate-500 hover:text-slate-800 hover:bg-slate-100',
                  )
                }
              >
                <Icon size={16} />
                {label}
              </NavLink>
            ))}
          </nav>

          {/* Account */}
          <div className="w-32 flex items-center justify-end gap-3">
            {isAuthenticated && (
              <>
                <span className="text-xs text-slate-500 truncate max-w-[96px]" title={accountLabel}>
                  {accountLabel}
                </span>
              </>
            )}
          </div>
        </div>
      </header>

      {/* Page Content */}
      <main className="flex-1 max-w-screen-2xl mx-auto w-full px-6 py-8 bg-slate-50/90">
        <Outlet />
      </main>
    </div>
  );
}
