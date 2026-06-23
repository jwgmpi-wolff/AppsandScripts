import { cn } from '@/lib/utils';
import type { ReactNode } from 'react';

interface CardProps {
  className?: string;
  children: ReactNode;
}

export function Card({ className, children }: CardProps) {
  return (
    <div className={cn('bg-white rounded-2xl shadow-sm border border-slate-100 p-6', className)}>
      {children}
    </div>
  );
}

export function CardHeader({ className, children }: CardProps) {
  return <div className={cn('mb-4', className)}>{children}</div>;
}

export function CardTitle({ className, children }: CardProps) {
  return <h3 className={cn('text-sm font-semibold text-slate-500 uppercase tracking-wide', className)}>{children}</h3>;
}

export function CardValue({ className, children }: CardProps) {
  return <div className={cn('text-3xl font-bold text-slate-900 mt-1', className)}>{children}</div>;
}

export function CardSubtitle({ className, children }: CardProps) {
  return <div className={cn('text-xs text-slate-400 mt-1', className)}>{children}</div>;
}
