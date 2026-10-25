import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Badge } from './Badge'

describe('Badge', () => {
  it('파란 정보 배지는 브랜드 노란 선택 표면과 분리한다', () => {
    render(<Badge tone="primary">편집 가능</Badge>)

    const badge = screen.getByText('편집 가능')
    expect(badge).toHaveClass('bg-selection', 'text-selection-foreground')
    expect(badge).not.toHaveClass('bg-accent', 'text-accent-foreground')
  })
})
