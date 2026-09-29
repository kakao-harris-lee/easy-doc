import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'

import { ThemeSwitch } from './ThemeSwitch'
import { THEME_STORAGE_KEY } from '../theme'
import { ThemeProvider } from '../theme/ThemeProvider'

function renderSwitch() {
  return render(
    <ThemeProvider>
      <ThemeSwitch />
    </ThemeProvider>,
  )
}

describe('ThemeSwitch', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('reflects the resolved theme through aria-checked', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    renderSwitch()

    const toggle = screen.getByRole('switch', { name: '다크 모드' })
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(toggle).toHaveAttribute('type', 'button')
  })

  it('switches light to dark and persists the explicit choice', async () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'light')
    const user = userEvent.setup()
    renderSwitch()
    const toggle = screen.getByRole('switch', { name: '다크 모드' })
    expect(toggle).toHaveAttribute('aria-checked', 'false')

    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')

    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
  })

  it('toggles from the resolved theme when the preference is system', async () => {
    const user = userEvent.setup()
    renderSwitch()
    const toggle = screen.getByRole('switch', { name: '다크 모드' })
    const resolvedDark = toggle.getAttribute('aria-checked') === 'true'

    await user.click(toggle)

    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe(resolvedDark ? 'light' : 'dark')
    await waitFor(() =>
      expect(toggle).toHaveAttribute('aria-checked', resolvedDark ? 'false' : 'true'),
    )
  })

  it('keeps a 44px hit area', () => {
    renderSwitch()

    expect(screen.getByRole('switch', { name: '다크 모드' })).toHaveClass('h-11')
  })
})
