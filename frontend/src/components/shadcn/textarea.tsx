import * as React from 'react'

import { cn } from '../../lib/utils'

const Textarea = React.forwardRef<HTMLTextAreaElement, React.ComponentProps<'textarea'>>(
  function Textarea({ className, ...props }, ref) {
    return (
      <textarea
        ref={ref}
        data-slot="textarea"
        className={cn(
          'border-input placeholder:text-muted-foreground aria-invalid:border-danger aria-invalid:ring-danger/20 flex field-sizing-content min-h-16 w-full rounded-md border bg-card px-2.5 py-2 text-base shadow-xs transition-[color,box-shadow] disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:ring-3 ',
          className,
        )}
        {...props}
      />
    )
  },
)

export { Textarea }
