'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

export function Table({
  caption,
  className,
  children,
}: {
  caption: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <div className="w-full overflow-x-auto rounded-[12px] border border-border">
      <table className={cn('w-full border-collapse text-left', className)}>
        <caption className="sr-only">{caption}</caption>
        {children}
      </table>
    </div>
  )
}

export function THead({ children }: { children: React.ReactNode }) {
  return <thead className="bg-secondary">{children}</thead>
}

export function TBody({ children }: { children: React.ReactNode }) {
  return <tbody>{children}</tbody>
}

export function TR({
  className,
  ...props
}: React.HTMLAttributes<HTMLTableRowElement>) {
  return (
    <tr
      className={cn(
        'border-b border-border last:border-0 hover:bg-secondary/50',
        className,
      )}
      {...props}
    />
  )
}

export function TH({
  className,
  scope = 'col',
  ...props
}: React.ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th
      scope={scope}
      className={cn(
        'whitespace-nowrap px-4 py-3 text-sm font-bold text-foreground',
        className,
      )}
      {...props}
    />
  )
}

export function TD({
  className,
  ...props
}: React.TdHTMLAttributes<HTMLTableCellElement>) {
  return (
    <td
      className={cn('px-4 py-3 text-[15px] text-foreground align-middle', className)}
      {...props}
    />
  )
}
