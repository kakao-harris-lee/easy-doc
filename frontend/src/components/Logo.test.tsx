import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Logo } from './Logo'

describe('Logo', () => {
  it('icon-320.png와 EASY-DOC AI 이름을 나란히 그린다', () => {
    const { container } = render(<Logo />)

    expect(container.querySelector('img')).toHaveAttribute('src', '/icons/icon-320.png')
    expect(container.querySelector('img')).toHaveAttribute('width', '44')
    expect(container.querySelector('img')).toHaveAttribute('height', '44')
    expect(screen.getByText('EASY-DOC AI')).toBeInTheDocument()
    expect(screen.getByText('쉬운 글로 다시 쓰는 도구')).toBeInTheDocument()
  })

  it('compact는 아이콘을 줄이고 lg 미만에서 부제를 감춘다', () => {
    const { container } = render(<Logo compact />)

    expect(container.querySelector('img')).toHaveClass('size-8', 'lg:size-11')
    expect(screen.getByText('쉬운 글로 다시 쓰는 도구')).toHaveClass('hidden', 'lg:block')
    expect(screen.getByText('EASY-DOC AI')).toBeInTheDocument()
  })
})
