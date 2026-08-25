'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

/**
 * Accessible tooltip: shows on hover AND keyboard focus. The trigger must have
 * its own accessible name (e.g. aria-label) — the tooltip is purely visual
 * reinforcement (aria-hidden), so screen readers rely on the label.
 */
export function Tooltip({
  label,
  children,
  side = 'top',
  className,
}: {
  label: string
  children: React.ReactNode
  side?: 'top' | 'bottom' | 'left' | 'right'
  className?: string
}) {
  const [open, setOpen] = React.useState(false)

  const pos: Record<string, string> = {
    top: 'bottom-full left-1/2 -translate-x-1/2 mb-2',
    bottom: 'top-full left-1/2 -translate-x-1/2 mt-2',
    left: 'right-full top-1/2 -translate-y-1/2 mr-2',
    right: 'left-full top-1/2 -translate-y-1/2 ml-2',
  }

  return (
    <span
      className={cn('relative inline-flex', className)}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
    >
      {children}
      {open && (
        <span
          role="tooltip"
          aria-hidden="true"
          className={cn(
            'pointer-events-none absolute z-50 whitespace-nowrap rounded-md bg-foreground px-2.5 py-1.5 text-sm font-medium text-background shadow-sm',
            pos[side],
          )}
        >
          {label}
        </span>
      )}
    </span>
  )
}
