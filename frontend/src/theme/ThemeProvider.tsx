import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'

import {
  applyThemePreference,
  readThemePreference,
  THEME_STORAGE_KEY,
  ThemeContext,
  writeThemePreference,
} from './theme'
import type { ResolvedTheme, ThemeContextValue, ThemePreference } from './theme'

function initialThemeState(): { preference: ThemePreference; resolvedTheme: ResolvedTheme } {
  const preference = readThemePreference()
  const resolvedTheme = applyThemePreference(preference)
  return { preference, resolvedTheme }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState(initialThemeState)

  const applyPreference = useCallback((preference: ThemePreference): void => {
    const resolvedTheme = applyThemePreference(preference)
    setState((current) =>
      current.preference === preference && current.resolvedTheme === resolvedTheme
        ? current
        : { preference, resolvedTheme },
    )
  }, [])

  const setPreference = useCallback(
    (preference: ThemePreference): void => {
      writeThemePreference(preference)
      applyPreference(preference)
    },
    [applyPreference],
  )

  useEffect(() => {
    function handleStorage(event: StorageEvent): void {
      // `key === null` is emitted by localStorage.clear(), including removal of
      // the preference. Invalid or missing values intentionally fall back to system.
      if (event.key !== THEME_STORAGE_KEY && event.key !== null) {
        return
      }
      applyPreference(readThemePreference())
    }

    window.addEventListener('storage', handleStorage)
    return () => window.removeEventListener('storage', handleStorage)
  }, [applyPreference])

  useEffect(() => {
    if (state.preference !== 'system') {
      return
    }

    let mediaQuery: MediaQueryList | null = null
    try {
      mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    } catch {
      return
    }

    const handleSystemChange = (): void => {
      // Resolve again instead of trusting the event payload so test doubles and
      // browsers with legacy MediaQueryList events behave consistently.
      applyPreference('system')
    }

    mediaQuery.addEventListener?.('change', handleSystemChange)
    return () => mediaQuery?.removeEventListener?.('change', handleSystemChange)
  }, [applyPreference, state.preference])

  const value = useMemo<ThemeContextValue>(
    () => ({
      preference: state.preference,
      resolvedTheme: state.resolvedTheme,
      setPreference,
    }),
    [setPreference, state.preference, state.resolvedTheme],
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}
