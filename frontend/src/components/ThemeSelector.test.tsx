import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ThemeSelector } from './ThemeSelector'
import { THEME_STORAGE_KEY } from '../theme'
import { ThemeProvider } from '../theme/ThemeProvider'

type Listener = () => void

function createMediaQueryList(initialMatches = false) {
  let matches = initialMatches
  const listeners = new Set<Listener>()
  const media = {
    media: '(prefers-color-scheme: dark)',
    get matches() {
      return matches
    },
    onchange: null,
    addEventListener: (_type: string, listener: Listener) => listeners.add(listener),
    removeEventListener: (_type: string, listener: Listener) => listeners.delete(listener),
    setMatches(next: boolean) {
      matches = next
      listeners.forEach((listener) => listener())
    },
  }
  return media as unknown as MediaQueryList & { setMatches: (next: boolean) => void }
}

function renderSelector() {
  return render(
    <ThemeProvider>
      <ThemeSelector />
    </ThemeProvider>,
  )
}

describe('ThemeSelector', () => {
  let originalMatchMedia: typeof window.matchMedia

  beforeEach(() => {
    window.localStorage.clear()
    originalMatchMedia = window.matchMedia
  })

  afterEach(() => {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: originalMatchMedia,
    })
    vi.restoreAllMocks()
  })

  it('shows the current choice and persists an explicit selection', async () => {
    const user = userEvent.setup()
    renderSelector()

    const selector = screen.getByRole('combobox', { name: '화면 테마' })
    expect(selector).toHaveValue('system')
    expect(screen.getByText('테마')).toBeInTheDocument()

    await user.selectOptions(selector, 'dark')

    expect(selector).toHaveValue('dark')
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
  })

  it('follows OS changes only while system is selected', async () => {
    const media = createMediaQueryList(false)
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: vi.fn(() => media),
    })

    const user = userEvent.setup()
    renderSelector()
    const selector = screen.getByRole('combobox', { name: '화면 테마' })

    media.setMatches(true)
    await waitFor(() => expect(document.documentElement).toHaveAttribute('data-theme', 'dark'))
    expect(selector).toHaveValue('system')

    await user.selectOptions(selector, 'light')
    media.setMatches(false)
    await waitFor(() => expect(document.documentElement).toHaveAttribute('data-theme', 'light'))
    expect(selector).toHaveValue('light')

    media.setMatches(true)
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
  })

  it('syncs explicit changes and removal from another tab', async () => {
    renderSelector()
    const selector = screen.getByRole('combobox', { name: '화면 테마' })

    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: 'dark' }))
    await waitFor(() => expect(selector).toHaveValue('dark'))

    window.localStorage.removeItem(THEME_STORAGE_KEY)
    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: null }))
    await waitFor(() => expect(selector).toHaveValue('system'))

    window.localStorage.setItem(THEME_STORAGE_KEY, 'light')
    window.dispatchEvent(new StorageEvent('storage', { key: null, newValue: null }))
    await waitFor(() => expect(selector).toHaveValue('light'))
  })
})
