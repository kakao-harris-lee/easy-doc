'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

export function Card({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'rounded-[12px] border border-border bg-card text-card-foreground shadow-[0_1px_2px_rgba(20,33,31,0.04)]',
        className,
      )}
      {...props}
    />
  )
}

export function CardHeader({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'flex flex-col gap-1 border-b border-border px-5 py-4',
        className,
      )}
      {...props}
    />
  )
}

export function CardTitle({
  className,
  as: As = 'h3',
  ...props
}: React.HTMLAttributes<HTMLHeadingElement> & { as?: React.ElementType }) {
  return (
    <As
      className={cn('text-lg font-bold text-foreground', className)}
      {...props}
    />
  )
}

export function CardDescription({
  className,
  ...props
}: React.HTMLAttributes<HTMLParagraphElement>) {
  return (
    <p
      className={cn('text-sm text-muted-foreground', className)}
      {...props}
    />
  )
}

export function CardContent({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('px-5 py-4', className)} {...props} />
}

export function CardFooter({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'flex items-center gap-3 border-t border-border px-5 py-4',
        className,
      )}
      {...props}
    />
  )
}
