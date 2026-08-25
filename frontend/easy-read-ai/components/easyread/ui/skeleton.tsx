'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

export function Skeleton({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      aria-hidden="true"
      className={cn('er-skeleton rounded-md', className)}
      {...props}
    />
  )
}

/** A labelled loading region that announces to screen readers. */
export function SkeletonBlock({
  label = '불러오는 중입니다',
  className,
  children,
}: {
  label?: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <div role="status" aria-live="polite" className={className}>
      <span className="sr-only">{label}</span>
      {children}
    </div>
  )
}
