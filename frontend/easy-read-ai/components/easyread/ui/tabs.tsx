'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

interface TabItem {
  value: string
  label: string
}

/**
 * Accessible tab list using roving arrow-key navigation and proper
 * tab/tabpanel roles. Controlled via `value` / `onValueChange`.
 */
export function Tabs({
  items,
  value,
  onValueChange,
  idBase = 'tabs',
  className,
}: {
  items: TabItem[]
  value: string
  onValueChange: (v: string) => void
  idBase?: string
  className?: string
}) {
  const refs = React.useRef<(HTMLButtonElement | null)[]>([])

  function onKeyDown(e: React.KeyboardEvent, index: number) {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return
    e.preventDefault()
    const dir = e.key === 'ArrowRight' ? 1 : -1
    const next = (index + dir + items.length) % items.length
    onValueChange(items[next].value)
    refs.current[next]?.focus()
  }

  return (
    <div
      role="tablist"
      aria-orientation="horizontal"
      className={cn('flex gap-1 border-b border-border', className)}
    >
      {items.map((item, i) => {
        const active = item.value === value
        return (
          <button
            key={item.value}
            ref={(el) => {
              refs.current[i] = el
            }}
            role="tab"
            id={`${idBase}-tab-${item.value}`}
            aria-selected={active}
            aria-controls={`${idBase}-panel-${item.value}`}
            tabIndex={active ? 0 : -1}
            onKeyDown={(e) => onKeyDown(e, i)}
            onClick={() => onValueChange(item.value)}
            className={cn(
              '-mb-px border-b-2 px-4 py-2.5 text-[15px] font-semibold transition-colors focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring',
              active
                ? 'border-primary text-primary'
                : 'border-transparent text-muted-foreground hover:text-foreground',
            )}
          >
            {item.label}
          </button>
        )
      })}
    </div>
  )
}

export function TabPanel({
  value,
  active,
  idBase = 'tabs',
  className,
  children,
}: {
  value: string
  active: boolean
  idBase?: string
  className?: string
  children: React.ReactNode
}) {
  if (!active) return null
  return (
    <div
      role="tabpanel"
      id={`${idBase}-panel-${value}`}
      aria-labelledby={`${idBase}-tab-${value}`}
      tabIndex={0}
      className={cn('focus-visible:outline-none', className)}
    >
      {children}
    </div>
  )
}
