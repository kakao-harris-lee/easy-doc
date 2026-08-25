'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'
import { Check, AlertTriangle, XCircle, Info, Circle } from 'lucide-react'

type Tone = 'neutral' | 'success' | 'warning' | 'danger' | 'info' | 'primary'

const tones: Record<Tone, string> = {
  neutral: 'bg-secondary text-secondary-foreground border-border',
  success: 'bg-success-surface text-success border-[color:var(--success)]/25',
  warning: 'bg-warning-surface text-warning border-[color:var(--warning)]/25',
  danger: 'bg-danger-surface text-danger border-[color:var(--danger)]/25',
  info: 'bg-info-surface text-info border-[color:var(--info)]/25',
  primary: 'bg-accent text-accent-foreground border-[color:var(--primary)]/25',
}

const toneIcon: Record<Tone, React.ElementType | null> = {
  neutral: null,
  success: Check,
  warning: AlertTriangle,
  danger: XCircle,
  info: Info,
  primary: Circle,
}

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  tone?: Tone
  /** Show a shape icon so status is never conveyed by color alone. */
  withIcon?: boolean
}

export function Badge({
  className,
  tone = 'neutral',
  withIcon = true,
  children,
  ...props
}: BadgeProps) {
  const Icon = toneIcon[tone]
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-sm font-medium leading-6',
        tones[tone],
        className,
      )}
      {...props}
    >
      {withIcon && Icon && <Icon className="size-3.5 shrink-0" aria-hidden="true" />}
      {children}
    </span>
  )
}
