import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Button } from './Button'

describe('Button', () => {
  it('로딩 스피너는 prefers-reduced-motion 에서 멈춘다 (DESIGN.md §12)', () => {
    render(<Button loading>저장</Button>)

    const spinner = screen.getByRole('button').querySelector('[aria-hidden="true"]')
    expect(spinner).not.toBeNull()
    expect(spinner).toHaveClass('animate-spin')
    expect(spinner).toHaveClass('motion-reduce:animate-none')
  })

  it('로딩 중이 아니면 스피너를 그리지 않는다', () => {
    render(<Button>저장</Button>)

    expect(screen.getByRole('button').querySelector('[aria-hidden="true"]')).toBeNull()
  })

  it.each([
    ['primary', 'bg-primary', 'enabled:hover:bg-primary-hover', 'enabled:active:bg-primary-active'],
    ['secondary', 'bg-card', 'enabled:hover:bg-secondary', 'enabled:active:bg-muted'],
    ['outline', 'border-input', 'enabled:hover:bg-secondary', 'enabled:active:bg-muted'],
    ['ghost', 'bg-transparent', 'enabled:hover:bg-secondary', 'enabled:active:bg-muted'],
    ['danger', 'bg-danger', 'enabled:hover:brightness-95', 'enabled:active:brightness-90'],
  ] as const)(
    '%s 버튼은 상태별 색 역할을 사용한다',
    (variant, baseClass, hoverClass, activeClass) => {
      render(
        <Button variant={variant} disabled>
          저장
        </Button>,
      )

      const button = screen.getByRole('button', { name: '저장' })
      expect(button).toHaveClass(baseClass, hoverClass, activeClass, 'disabled:opacity-50')
      expect(button).toBeDisabled()
      expect(button).not.toHaveClass('hover:bg-accent')
    },
  )
})
