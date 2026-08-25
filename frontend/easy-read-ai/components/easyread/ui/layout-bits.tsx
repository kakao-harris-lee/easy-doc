'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

/** Skip link — first focusable element, jumps to #main. */
export function SkipLink() {
  return (
    <a
      href="#main"
      className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[70] focus:rounded-md focus:bg-primary focus:px-4 focus:py-2 focus:text-primary-foreground focus:shadow-lg"
    >
      본문으로 건너뛰기
    </a>
  )
}

export function PageHeader({
  title,
  description,
  actions,
  className,
}: {
  title: string
  description?: string
  actions?: React.ReactNode
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between',
        className,
      )}
    >
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-bold tracking-tight text-foreground text-balance">
          {title}
        </h1>
        {description && (
          <p className="text-[15px] leading-relaxed text-muted-foreground text-pretty">
            {description}
          </p>
        )}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

/** Compact stat tile used on dashboards — label + value + optional delta. */
export function StatTile({
  label,
  value,
  sub,
  icon: Icon,
  className,
}: {
  label: string
  value: string
  sub?: React.ReactNode
  icon?: React.ElementType
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex flex-col gap-1 rounded-[12px] border border-border bg-card px-5 py-4',
        className,
      )}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium text-muted-foreground">{label}</span>
        {Icon && <Icon className="size-4 text-muted-foreground" aria-hidden="true" />}
      </div>
      <span className="text-2xl font-bold tabular-nums text-foreground">{value}</span>
      {sub && <span className="text-sm text-muted-foreground">{sub}</span>}
    </div>
  )
}
