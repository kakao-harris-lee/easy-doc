'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'
import { Tooltip } from './tooltip'

export interface IconButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  /** Required accessible name. Also shown as a visible tooltip. */
  label: string
  side?: 'top' | 'bottom' | 'left' | 'right'
  size?: 'sm' | 'md'
}

/**
 * Icon-only button. Enforces an accessible name (`label`) and a visible
 * tooltip so it satisfies KWCAG for controls without a text label.
 */
export const IconButton = React.forwardRef<HTMLButtonElement, IconButtonProps>(
  ({ label, side = 'top', size = 'md', className, children, ...props }, ref) => {
    return (
      <Tooltip label={label} side={side}>
        <button
          ref={ref}
          type="button"
          aria-label={label}
          className={cn(
            'inline-flex items-center justify-center rounded-[10px] border border-transparent text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-50',
            size === 'sm' ? 'size-9' : 'size-11',
            className,
          )}
          {...props}
        >
          {children}
        </button>
      </Tooltip>
    )
  },
)
IconButton.displayName = 'IconButton'
