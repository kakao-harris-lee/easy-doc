import { createContext, useContext } from 'react'

export const THEME_STORAGE_KEY = 'easy-doc-theme'

export type ThemePreference = 'system' | 'light' | 'dark'
export type ResolvedTheme = Exclude<ThemePreference, 'system'>

type StorageLike = Pick<Storage, 'getItem' | 'setItem'>

export interface ThemeContextValue {
  preference: ThemePreference
  resolvedTheme: ResolvedTheme
  setPreference: (preference: ThemePreference) => void
}

export const THEME_COLORS: Record<ResolvedTheme, string> = {
  light: '#F7F9FC',
  dark: '#111827',
}

function getLocalStorage(): StorageLike | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export function isThemePreference(value: string | null): value is ThemePreference {
  return value === 'system' || value === 'light' || value === 'dark'
}

export function readThemePreference(
  storage: StorageLike | null = getLocalStorage(),
): ThemePreference {
  if (storage === null) {
    return 'system'
  }
  try {
    const value = storage.getItem(THEME_STORAGE_KEY)
    return isThemePreference(value) ? value : 'system'
  } catch {
    return 'system'
  }
}

export function writeThemePreference(
  preference: ThemePreference,
  storage: StorageLike | null = getLocalStorage(),
): void {
  if (storage === null) {
    return
  }
  try {
    storage.setItem(THEME_STORAGE_KEY, preference)
  } catch {
    // Private browsing and blocked storage must not prevent theme selection.
  }
}

export function getSystemTheme(): ResolvedTheme {
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  } catch {
    // Older browsers and test environments without matchMedia use the light fallback.
    return 'light'
  }
}

export function resolveTheme(
  preference: ThemePreference,
  systemTheme: ResolvedTheme = getSystemTheme(),
): ResolvedTheme {
  return preference === 'system' ? systemTheme : preference
}

export function applyResolvedTheme(theme: ResolvedTheme, documentRef: Document = document): void {
  const root = documentRef.documentElement
  root.dataset.theme = theme
  root.style.colorScheme = theme

  const themeColor = documentRef.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  themeColor?.setAttribute('content', THEME_COLORS[theme])
}

export function applyThemePreference(preference: ThemePreference): ResolvedTheme {
  const resolvedTheme = resolveTheme(preference)
  if (typeof document !== 'undefined') {
    applyResolvedTheme(resolvedTheme)
  }
  return resolvedTheme
}

export const ThemeContext = createContext<ThemeContextValue | null>(null)

export function useTheme(): ThemeContextValue {
  const value = useContext(ThemeContext)
  if (value === null) {
    throw new Error('useTheme는 ThemeProvider 안에서만 사용할 수 있습니다')
  }
  return value
}
