import type { ChangeEvent } from 'react'

import { cn } from '../lib/utils'
import { useTheme } from '../theme'
import type { ThemePreference } from '../theme'

const THEME_OPTIONS: ReadonlyArray<{ value: ThemePreference; label: string }> = [
  { value: 'system', label: '시스템' },
  { value: 'light', label: '라이트' },
  { value: 'dark', label: '다크' },
]

export function ThemeSelector({ className }: { className?: string }) {
  const { preference, setPreference } = useTheme()

  function handleChange(event: ChangeEvent<HTMLSelectElement>): void {
    const next = event.target.value
    if (next === 'system' || next === 'light' || next === 'dark') {
      setPreference(next)
    }
  }

  return (
    <label className={cn('inline-flex min-h-11 items-center gap-2 text-sm', className)}>
      <span className="shrink-0 font-medium text-muted-foreground">테마</span>
      <select
        aria-label="화면 테마"
        className="min-h-11 w-[5.5rem] rounded-md border border-input bg-card px-2 text-sm font-medium text-foreground outline-none hover:bg-secondary focus-visible:ring-2"
        value={preference}
        onChange={handleChange}
      >
        {THEME_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  )
}
