import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ConversionStages } from './ConversionStages'

describe('ConversionStages', () => {
  it('현재 단계 표식은 주색 배경과 주색 전경 글자를 사용한다', () => {
    render(<ConversionStages status="processing" />)

    const current = screen.getByText('쉬운 글 변환').closest('li')
    const marker = current?.querySelector('[aria-hidden="true"]')
    expect(marker).toHaveClass('bg-primary', 'text-primary-foreground')
    expect(marker).not.toHaveClass('bg-accent', 'bg-selection')
    expect(current).toHaveTextContent('진행 중')
  })
})
