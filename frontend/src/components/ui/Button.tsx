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
  sm: 'h-8 px-3 text-sm',
  md: 'h-10 px-4 text-sm',
  lg: 'h-12 px-5 text-base',
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
        // 포커스 링을 여기서 다시 정하지 않는다. `index.css` 의 `:focus-visible` 이
        // 3px·offset 2px·ring 색을 이미 그리고, DESIGN.md §11 이 요구하는 두께도 그
        // 한 곳에 있다. 예전에 있던 `focus-visible:outline-2` 삼종은 같은 말을 하면서
        // 두께만 2px 로 덮어써, 규정된 3px 이 앱 어디에도 그려지지 않게 만들었다.
        'inline-flex items-center justify-center gap-2 rounded-md font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50',
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
