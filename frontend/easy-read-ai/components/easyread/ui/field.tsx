'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'
import { AlertCircle } from 'lucide-react'

const inputBase =
  'w-full rounded-[10px] border bg-card px-3.5 text-[16px] text-foreground placeholder:text-muted-foreground transition-colors focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-60 disabled:bg-secondary'

export const Input = React.forwardRef<
  HTMLInputElement,
  React.InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }
>(({ className, invalid, ...props }, ref) => (
  <input
    ref={ref}
    aria-invalid={invalid || undefined}
    className={cn(
      inputBase,
      'h-11',
      invalid ? 'border-danger' : 'border-input',
      className,
    )}
    {...props}
  />
))
Input.displayName = 'Input'

export const Textarea = React.forwardRef<
  HTMLTextAreaElement,
  React.TextareaHTMLAttributes<HTMLTextAreaElement> & { invalid?: boolean }
>(({ className, invalid, ...props }, ref) => (
  <textarea
    ref={ref}
    aria-invalid={invalid || undefined}
    className={cn(
      inputBase,
      'min-h-32 py-3 leading-relaxed',
      invalid ? 'border-danger' : 'border-input',
      className,
    )}
    {...props}
  />
))
Textarea.displayName = 'Textarea'

export const Select = React.forwardRef<
  HTMLSelectElement,
  React.SelectHTMLAttributes<HTMLSelectElement> & { invalid?: boolean }
>(({ className, invalid, children, ...props }, ref) => (
  <select
    ref={ref}
    aria-invalid={invalid || undefined}
    className={cn(
      inputBase,
      'h-11 pr-8',
      invalid ? 'border-danger' : 'border-input',
      className,
    )}
    {...props}
  >
    {children}
  </select>
))
Select.displayName = 'Select'

/**
 * FormField wires label → control → hint/error together using ids so the
 * error message is announced (aria-describedby) and the invalid state is
 * associated with the control. Pass a single form control as children.
 */
export function FormField({
  id,
  label,
  hint,
  error,
  required,
  children,
  className,
}: {
  id: string
  label: string
  hint?: string
  error?: string
  required?: boolean
  children: React.ReactElement<any>
  className?: string
}) {
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined

  const control = React.cloneElement(children, {
    id,
    invalid: Boolean(error),
    'aria-describedby': describedBy,
    required,
  })

  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <label htmlFor={id} className="text-[15px] font-semibold text-foreground">
        {label}
        {required && (
          <span className="ml-1 text-danger" aria-hidden="true">
            *
          </span>
        )}
        {required && <span className="sr-only">필수 입력</span>}
      </label>
      {hint && (
        <p id={hintId} className="text-sm text-muted-foreground">
          {hint}
        </p>
      )}
      {control}
      {error && (
        <p
          id={errorId}
          role="alert"
          className="flex items-center gap-1.5 text-sm font-medium text-danger"
        >
          <AlertCircle className="size-4 shrink-0" aria-hidden="true" />
          {error}
        </p>
      )}
    </div>
  )
}
