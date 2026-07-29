import * as RadixDialog from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { ReactNode } from 'react';

interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children: ReactNode;
  className?: string;
}

export function Dialog({ open, onOpenChange, title, description, children, className }: DialogProps) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 bg-black/40 backdrop-blur-sm z-40 animate-in fade-in" />
        <RadixDialog.Content
          className={cn(
            'fixed top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 z-50',
            'bg-white rounded-2xl shadow-2xl p-6 w-full max-w-md',
            'focus:outline-none',
            className,
          )}
        >
          <div className="flex items-center justify-between mb-4">
            <RadixDialog.Title className="text-lg font-semibold text-slate-900">{title}</RadixDialog.Title>
            <RadixDialog.Close className="rounded-lg p-1 hover:bg-slate-100 transition-colors focus:outline-none focus:ring-2 focus:ring-indigo-500">
              <X size={18} className="text-slate-500" />
            </RadixDialog.Close>
          </div>
          {description && <p className="text-sm text-slate-500 mb-4">{description}</p>}
          {children}
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
