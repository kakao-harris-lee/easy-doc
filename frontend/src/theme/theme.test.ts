import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  applyResolvedTheme,
  getSystemTheme,
  readThemePreference,
  THEME_STORAGE_KEY,
  writeThemePreference,
} from './theme'

describe('theme persistence and DOM application', () => {
  beforeEach(() => {
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.style.colorScheme = ''
    window.localStorage.clear()
    document.head.querySelectorAll('meta[data-theme-test]').forEach((element) => element.remove())
    const themeColor = document.createElement('meta')
    themeColor.name = 'theme-color'
    themeColor.dataset.themeTest = 'true'
    document.head.append(themeColor)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('uses system when the preference is missing or invalid', () => {
    expect(readThemePreference()).toBe('system')

    window.localStorage.setItem(THEME_STORAGE_KEY, 'sepia')
    expect(readThemePreference()).toBe('system')

    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    expect(readThemePreference()).toBe('dark')
  })

  it('applies the resolved data attribute, browser color scheme, and theme color', () => {
    applyResolvedTheme('dark')

    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
    expect(document.querySelector('meta[name="theme-color"]')).toHaveAttribute('content', '#111827')

    applyResolvedTheme('light')
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    expect(document.documentElement.style.colorScheme).toBe('light')
    expect(document.querySelector('meta[name="theme-color"]')).toHaveAttribute('content', '#F7F9FC')
  })

  it('does not throw when storage is unavailable', () => {
    const brokenStorage = {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    }

    expect(readThemePreference(brokenStorage)).toBe('system')
    expect(() => writeThemePreference('dark', brokenStorage)).not.toThrow()
  })

  it('falls back to light when matchMedia is unavailable', () => {
    const originalMatchMedia = window.matchMedia
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: undefined })
    expect(getSystemTheme()).toBe('light')
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: originalMatchMedia,
    })
  })
})
