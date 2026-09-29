import { Moon, Sun } from 'lucide-react'

import { cn } from '../lib/utils'
import { useTheme } from '../theme'

export function ThemeSwitch({ className }: { className?: string }) {
  const { resolvedTheme, setPreference } = useTheme()
  const dark = resolvedTheme === 'dark'

  return (
    <button
      type="button"
      role="switch"
      aria-checked={dark}
      aria-label="다크 모드"
      onClick={() => setPreference(dark ? 'light' : 'dark')}
      className={cn('inline-flex h-11 shrink-0 items-center rounded-full', className)}
    >
      <span className="relative flex h-9 w-[68px] items-center justify-between rounded-full border border-border bg-secondary px-[9px] text-muted-foreground">
        <Sun className="size-4" aria-hidden="true" />
        <Moon className="size-4" aria-hidden="true" />
        <span
          aria-hidden="true"
          className={cn(
            'absolute left-[3px] top-[3px] flex size-7 items-center justify-center rounded-full bg-card shadow-sm transition-transform motion-reduce:transition-none',
            dark ? 'translate-x-8 text-foreground' : 'translate-x-0 text-primary',
          )}
        >
          {dark ? <Moon className="size-4" /> : <Sun className="size-4" fill="currentColor" />}
        </span>
      </span>
    </button>
  )
}
