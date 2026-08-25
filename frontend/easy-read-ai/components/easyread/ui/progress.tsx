'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

type Tone = 'primary' | 'success' | 'warning' | 'danger'

const fills: Record<Tone, string> = {
  primary: 'bg-primary',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-danger',
}

export function Progress({
  value,
  max = 100,
  tone = 'primary',
  label,
  showValue = false,
  className,
}: {
  value: number
  max?: number
  tone?: Tone
  label?: string
  showValue?: boolean
  className?: string
}) {
  const pct = Math.min(100, Math.max(0, (value / max) * 100))
  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      {(label || showValue) && (
        <div className="flex items-center justify-between text-sm">
          {label && <span className="font-medium text-foreground">{label}</span>}
          {showValue && (
            <span className="tabular-nums text-muted-foreground">
              {Math.round(pct)}%
            </span>
          )}
        </div>
      )}
      <div
        role="progressbar"
        aria-valuenow={Math.round(value)}
        aria-valuemin={0}
        aria-valuemax={max}
        aria-label={label}
        className="h-2.5 w-full overflow-hidden rounded-full bg-secondary"
      >
        <div
          className={cn('h-full rounded-full transition-[width]', fills[tone])}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
