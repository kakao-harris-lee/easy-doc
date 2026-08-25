'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'
import { CheckCircle2, AlertTriangle, XCircle, Info, X } from 'lucide-react'

type Tone = 'success' | 'warning' | 'danger' | 'info'

interface Toast {
  id: number
  tone: Tone
  title: string
  description?: string
}

interface ToastContextValue {
  notify: (t: Omit<Toast, 'id'>) => void
}

const ToastContext = React.createContext<ToastContextValue | null>(null)

export function useToast() {
  const ctx = React.useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within <ToastProvider>')
  return ctx
}

const toneMap: Record<Tone, { icon: React.ElementType; accent: string }> = {
  success: { icon: CheckCircle2, accent: 'text-success' },
  warning: { icon: AlertTriangle, accent: 'text-warning' },
  danger: { icon: XCircle, accent: 'text-danger' },
  info: { icon: Info, accent: 'text-info' },
}

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = React.useState<Toast[]>([])
  const idRef = React.useRef(0)

  const remove = React.useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const notify = React.useCallback(
    (t: Omit<Toast, 'id'>) => {
      const id = ++idRef.current
      setToasts((prev) => [...prev, { ...t, id }])
      window.setTimeout(() => remove(id), 5000)
    },
    [remove],
  )

  return (
    <ToastContext.Provider value={{ notify }}>
      {children}
      <div
        aria-live="polite"
        aria-atomic="false"
        className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-[calc(100vw-2rem)] max-w-sm flex-col gap-2"
      >
        {toasts.map((t) => {
          const { icon: Icon, accent } = toneMap[t.tone]
          return (
            <div
              key={t.id}
              role={t.tone === 'danger' ? 'alert' : 'status'}
              className="er-toast-in pointer-events-auto flex items-start gap-3 rounded-[12px] border border-border bg-card px-4 py-3 shadow-lg"
            >
              <Icon className={cn('mt-0.5 size-5 shrink-0', accent)} aria-hidden="true" />
              <div className="min-w-0 flex-1">
                <p className="font-bold text-foreground">{t.title}</p>
                {t.description && (
                  <p className="mt-0.5 text-sm leading-relaxed text-muted-foreground">
                    {t.description}
                  </p>
                )}
              </div>
              <button
                type="button"
                aria-label="알림 닫기"
                onClick={() => remove(t.id)}
                className="rounded-md p-0.5 text-muted-foreground hover:text-foreground focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
              >
                <X className="size-4" aria-hidden="true" />
              </button>
            </div>
          )
        })}
      </div>
    </ToastContext.Provider>
  )
}
