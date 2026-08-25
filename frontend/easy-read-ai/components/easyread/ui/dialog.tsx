'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'
import { X } from 'lucide-react'
import { IconButton } from './icon-button'

/**
 * Accessible modal dialog: role="dialog" aria-modal, Escape to close, focus
 * moves in on open and returns to the trigger on close, and a simple focus
 * trap keeps Tab within the dialog.
 */
export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
}: {
  open: boolean
  onClose: () => void
  title: string
  description?: string
  children?: React.ReactNode
  footer?: React.ReactNode
  size?: 'sm' | 'md' | 'lg'
}) {
  const panelRef = React.useRef<HTMLDivElement>(null)
  const previouslyFocused = React.useRef<HTMLElement | null>(null)
  const titleId = React.useId()
  const descId = React.useId()

  React.useEffect(() => {
    if (!open) return
    previouslyFocused.current = document.activeElement as HTMLElement
    const panel = panelRef.current
    const focusable = panel?.querySelector<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    )
    focusable?.focus()

    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
        return
      }
      if (e.key === 'Tab' && panel) {
        const items = Array.from(
          panel.querySelectorAll<HTMLElement>(
            'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
          ),
        ).filter((el) => !el.hasAttribute('disabled'))
        if (items.length === 0) return
        const first = items[0]
        const last = items[items.length - 1]
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault()
          last.focus()
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault()
          first.focus()
        }
      }
    }
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
      previouslyFocused.current?.focus()
    }
  }, [open, onClose])

  if (!open) return null

  const sizes = { sm: 'max-w-sm', md: 'max-w-lg', lg: 'max-w-2xl' }

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center p-4 sm:items-center">
      <div
        className="absolute inset-0 bg-foreground/40"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descId : undefined}
        className={cn(
          'relative z-10 w-full rounded-[14px] border border-border bg-card shadow-xl',
          sizes[size],
        )}
      >
        <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-4">
          <div className="flex flex-col gap-1">
            <h2 id={titleId} className="text-lg font-bold text-foreground">
              {title}
            </h2>
            {description && (
              <p id={descId} className="text-sm text-muted-foreground">
                {description}
              </p>
            )}
          </div>
          <IconButton label="닫기" size="sm" onClick={onClose}>
            <X className="size-5" aria-hidden="true" />
          </IconButton>
        </div>
        {children && <div className="px-5 py-4">{children}</div>}
        {footer && (
          <div className="flex flex-wrap items-center justify-end gap-3 border-t border-border px-5 py-4">
            {footer}
          </div>
        )}
      </div>
    </div>
  )
}
