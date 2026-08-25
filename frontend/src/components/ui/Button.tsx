import { forwardRef, type ButtonHTMLAttributes } from 'react'

import { cn } from '../../lib/utils'

type Variant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
type Size = 'sm' | 'md' | 'lg'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: Size
  loading?: boolean
  fullWidth?: boolean
}

const variants: Record<Variant, string> = {
  primary: 'bg-primary text-primary-foreground hover:bg-primary-hover',
  secondary: 'border border-border bg-secondary text-secondary-foreground hover:bg-accent',
  outline: 'border border-input bg-card text-foreground hover:bg-secondary',
  ghost: 'bg-transparent text-foreground hover:bg-secondary',
  danger: 'bg-danger text-danger-foreground hover:brightness-95',
}

const sizes: Record<Size, string> = {
  sm: 'h-9 px-3 text-sm',
  md: 'h-11 px-4 text-base',
  lg: 'h-12 px-6 text-[17px]',
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
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
  ) => (
    <button
      ref={ref}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-[10px] font-semibold transition-colors focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50',
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
          className="size-4 animate-spin rounded-full border-2 border-current border-r-transparent"
        />
      )}
      {children}
    </button>
  ),
)
Button.displayName = 'Button'
