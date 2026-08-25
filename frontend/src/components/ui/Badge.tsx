import type { HTMLAttributes } from 'react'
import { AlertTriangle, Check, Circle, Info, XCircle } from 'lucide-react'

import { cn } from '../../lib/utils'

type Tone = 'neutral' | 'success' | 'warning' | 'danger' | 'info' | 'primary'

const tones: Record<Tone, string> = {
  neutral: 'border-border bg-secondary text-secondary-foreground',
  success: 'border-success/25 bg-success-surface text-success',
  warning: 'border-warning/25 bg-warning-surface text-warning',
  danger: 'border-danger/25 bg-danger-surface text-danger',
  info: 'border-info/25 bg-info-surface text-info',
  primary: 'border-primary/25 bg-accent text-accent-foreground',
}

const icons = {
  neutral: null,
  success: Check,
  warning: AlertTriangle,
  danger: XCircle,
  info: Info,
  primary: Circle,
}

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: Tone
  withIcon?: boolean
}

export function Badge({
  className,
  tone = 'neutral',
  withIcon = true,
  children,
  ...props
}: BadgeProps) {
  const Icon = icons[tone]
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
