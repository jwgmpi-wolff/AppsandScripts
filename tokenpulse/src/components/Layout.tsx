import { NavLink, Outlet } from 'react-router-dom';
import { BarChart3, Zap, FolderOpen, Cpu, Bell, Activity } from 'lucide-react';
import { cn } from '@/lib/utils';

const navItems = [
  { to: '/', label: 'Dashboard', icon: BarChart3, end: true },
  { to: '/usage', label: 'Usage Explorer', icon: Activity, end: false },
  { to: '/projects', label: 'Projects', icon: FolderOpen, end: false },
  { to: '/models', label: 'Models', icon: Cpu, end: false },
  { to: '/alerts', label: 'Alerts', icon: Bell, end: false },
];

export function Layout() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50/30 flex flex-col">
      {/* Top Nav */}
      <header className="sticky top-0 z-30 bg-white/80 backdrop-blur-md border-b border-slate-200 shadow-sm">
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

          {/* Right side placeholder */}
          <div className="w-32" />
        </div>
      </header>

      {/* Page Content */}
      <main className="flex-1 max-w-screen-2xl mx-auto w-full px-6 py-8">
        <Outlet />
      </main>
    </div>
  );
}
