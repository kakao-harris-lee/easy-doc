'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

/** Wordmark + mark. The mark is a simple "speech + check" glyph. */
export function Logo({
  variant = 'default',
  className,
}: {
  variant?: 'default' | 'admin'
  className?: string
}) {
  const isAdmin = variant === 'admin'
  return (
    <span className={cn('inline-flex items-center gap-2.5', className)}>
      <span
        aria-hidden="true"
        className={cn(
          'flex size-9 items-center justify-center rounded-[10px] text-primary-foreground',
          isAdmin ? 'bg-admin' : 'bg-primary',
        )}
      >
        <svg viewBox="0 0 24 24" fill="none" className="size-5" aria-hidden="true">
          <path
            d="M4 5.5A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5v9A1.5 1.5 0 0 1 18.5 16H9l-4 4v-4H5.5"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinejoin="round"
            fill="none"
            transform="translate(0 0)"
          />
          <path
            d="M8.5 10.2l2.2 2.2 4.3-4.6"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </span>
      <span className="flex flex-col leading-none">
        <span className="text-[15px] font-extrabold tracking-tight text-foreground">
          Easy-Read AI
        </span>
        <span className="mt-0.5 text-[11px] font-medium text-muted-foreground">
          {isAdmin ? '운영 관리자 콘솔' : '쉬운 우리말 변환'}
        </span>
      </span>
    </span>
  )
}
