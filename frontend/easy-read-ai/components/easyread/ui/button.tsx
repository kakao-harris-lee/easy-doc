'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

type Variant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
type Size = 'sm' | 'md' | 'lg'

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: Size
  loading?: boolean
  fullWidth?: boolean
}

const base =
  'inline-flex items-center justify-center gap-2 rounded-[10px] font-semibold transition-colors focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-50 disabled:cursor-not-allowed select-none'

const variants: Record<Variant, string> = {
  primary:
    'bg-primary text-primary-foreground hover:bg-[var(--primary-hover)] active:bg-[var(--primary-hover)]',
  secondary:
    'bg-secondary text-secondary-foreground border border-border hover:bg-accent',
  outline:
    'bg-card text-foreground border border-input hover:bg-secondary',
  ghost: 'bg-transparent text-foreground hover:bg-secondary',
  danger:
    'bg-danger text-danger-foreground hover:brightness-95 active:brightness-90',
}

const sizes: Record<Size, string> = {
  sm: 'h-9 px-3 text-sm',
  md: 'h-11 px-4 text-base',
  lg: 'h-12 px-6 text-[17px]',
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      className,
      variant = 'primary',
      size = 'md',
      loading = false,
      fullWidth = false,
      disabled,
      children,
      ...props
    },
    ref,
  ) => {
    return (
      <button
        ref={ref}
        className={cn(
          base,
          variants[variant],
          sizes[size],
          fullWidth && 'w-full',
          className,
        )}
        disabled={disabled || loading}
        aria-busy={loading || undefined}
        {...props}
      >
        {loading && (
          <span
            aria-hidden="true"
            className="size-4 rounded-full border-2 border-current border-r-transparent animate-spin"
          />
        )}
        {children}
      </button>
    )
  },
)
Button.displayName = 'Button'
