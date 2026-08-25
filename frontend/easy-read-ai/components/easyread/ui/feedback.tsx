'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'
import {
  CheckCircle2,
  AlertTriangle,
  XCircle,
  Info,
  Inbox,
  RefreshCw,
} from 'lucide-react'
import { Button } from './button'

type Tone = 'success' | 'warning' | 'danger' | 'info'

const styles: Record<Tone, { wrap: string; icon: React.ElementType }> = {
  success: {
    wrap: 'bg-success-surface border-[color:var(--success)]/25 text-success',
    icon: CheckCircle2,
  },
  warning: {
    wrap: 'bg-warning-surface border-[color:var(--warning)]/25 text-warning',
    icon: AlertTriangle,
  },
  danger: {
    wrap: 'bg-danger-surface border-[color:var(--danger)]/25 text-danger',
    icon: XCircle,
  },
  info: {
    wrap: 'bg-info-surface border-[color:var(--info)]/25 text-info',
    icon: Info,
  },
}

/** Inline status/alert message. Uses role="status" (or alert for danger). */
export function StatusMessage({
  tone = 'info',
  title,
  children,
  className,
}: {
  tone?: Tone
  title?: string
  children?: React.ReactNode
  className?: string
}) {
  const s = styles[tone]
  const Icon = s.icon
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={cn(
        'flex items-start gap-3 rounded-[10px] border px-4 py-3',
        s.wrap,
        className,
      )}
    >
      <Icon className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
      <div className="text-[15px] leading-relaxed text-foreground">
        {title && <p className="font-bold text-foreground">{title}</p>}
        {children}
      </div>
    </div>
  )
}

/** Empty state with optional action. */
export function EmptyState({
  icon: Icon = Inbox,
  title,
  description,
  action,
  className,
}: {
  icon?: React.ElementType
  title: string
  description?: string
  action?: React.ReactNode
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-3 rounded-[12px] border border-dashed border-border bg-card px-6 py-14 text-center',
        className,
      )}
    >
      <span className="flex size-12 items-center justify-center rounded-full bg-secondary">
        <Icon className="size-6 text-muted-foreground" aria-hidden="true" />
      </span>
      <h3 className="text-lg font-bold text-foreground">{title}</h3>
      {description && (
        <p className="max-w-sm text-[15px] leading-relaxed text-muted-foreground">
          {description}
        </p>
      )}
      {action && <div className="mt-1">{action}</div>}
    </div>
  )
}

/** Error state with a retry affordance. */
export function ErrorState({
  title = '문제가 발생했습니다',
  description = '잠시 후 다시 시도해 주세요. 계속 문제가 발생하면 관리자에게 문의하세요.',
  onRetry,
  className,
}: {
  title?: string
  description?: string
  onRetry?: () => void
  className?: string
}) {
  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-center justify-center gap-3 rounded-[12px] border border-[color:var(--danger)]/30 bg-danger-surface px-6 py-14 text-center',
        className,
      )}
    >
      <span className="flex size-12 items-center justify-center rounded-full bg-card">
        <XCircle className="size-6 text-danger" aria-hidden="true" />
      </span>
      <h3 className="text-lg font-bold text-foreground">{title}</h3>
      <p className="max-w-sm text-[15px] leading-relaxed text-foreground/80">
        {description}
      </p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry} className="mt-1">
          <RefreshCw className="size-4" aria-hidden="true" />
          다시 시도
        </Button>
      )}
    </div>
  )
}
