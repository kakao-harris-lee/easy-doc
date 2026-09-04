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
})
