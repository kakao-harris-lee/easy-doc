'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

interface MenuItem {
  label: string
  icon?: React.ElementType
  onSelect?: () => void
  danger?: boolean
}

/**
 * Small accessible dropdown menu. Opens on click, closes on outside click,
 * Escape, or selection. Trigger is passed as a render prop so callers control
 * its markup and accessible name.
 */
export function Menu({
  trigger,
  items,
  header,
  align = 'end',
}: {
  trigger: (props: {
    open: boolean
    toggle: () => void
    ref: React.Ref<HTMLButtonElement>
  }) => React.ReactNode
  items: MenuItem[]
  header?: React.ReactNode
  align?: 'start' | 'end'
}) {
  const [open, setOpen] = React.useState(false)
  const btnRef = React.useRef<HTMLButtonElement>(null)
  const menuRef = React.useRef<HTMLDivElement>(null)

  React.useEffect(() => {
    if (!open) return
    function onClick(e: MouseEvent) {
      if (
        !menuRef.current?.contains(e.target as Node) &&
        !btnRef.current?.contains(e.target as Node)
      ) {
        setOpen(false)
      }
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setOpen(false)
        btnRef.current?.focus()
      }
    }
    document.addEventListener('mousedown', onClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div className="relative">
      {trigger({ open, toggle: () => setOpen((o) => !o), ref: btnRef })}
      {open && (
        <div
          ref={menuRef}
          role="menu"
          className={cn(
            'absolute z-50 mt-2 min-w-56 rounded-[12px] border border-border bg-popover p-1.5 shadow-lg',
            align === 'end' ? 'right-0' : 'left-0',
          )}
        >
          {header && (
            <div className="border-b border-border px-3 py-2.5">{header}</div>
          )}
          {items.map((item, i) => {
            const Icon = item.icon
            return (
              <button
                key={i}
                role="menuitem"
                onClick={() => {
                  item.onSelect?.()
                  setOpen(false)
                }}
                className={cn(
                  'flex w-full items-center gap-2.5 rounded-md px-3 py-2 text-left text-[15px] font-medium transition-colors focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring',
                  item.danger
                    ? 'text-danger hover:bg-danger-surface'
                    : 'text-foreground hover:bg-secondary',
                )}
              >
                {Icon && <Icon className="size-4 shrink-0" aria-hidden="true" />}
                {item.label}
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
