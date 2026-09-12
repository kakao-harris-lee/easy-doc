import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Logo } from './Logo'

describe('Logo', () => {
  it('icon-320.png와 EASY-DOC AI 이름을 나란히 그린다', () => {
    const { container } = render(<Logo />)

    expect(container.querySelector('img')).toHaveAttribute('src', '/icons/icon-320.png')
    expect(screen.getByText('EASY-DOC AI')).toBeInTheDocument()
    expect(screen.getByText('쉬운 우리말 변환')).toBeInTheDocument()
  })
})
