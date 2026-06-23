import * as RadixProgress from '@radix-ui/react-progress';
import { cn } from '@/lib/utils';

interface ProgressBarProps {
  value: number; // 0-100
  className?: string;
  color?: string;
}

export function ProgressBar({ value, className, color }: ProgressBarProps) {
  const clamped = Math.min(100, Math.max(0, value));
  return (
    <RadixProgress.Root
      className={cn('relative h-2 overflow-hidden rounded-full bg-slate-100', className)}
      value={clamped}
    >
      <RadixProgress.Indicator
        className="h-full rounded-full transition-all duration-500"
        style={{
          width: `${clamped}%`,
          backgroundColor: color ?? (clamped >= 90 ? '#ef4444' : clamped >= 70 ? '#f59e0b' : '#6366f1'),
        }}
      />
    </RadixProgress.Root>
  );
}
