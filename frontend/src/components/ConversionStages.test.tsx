import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ConversionStages } from './ConversionStages'

describe('ConversionStages', () => {
  it('현재 단계 표식은 브랜드 노란색 대신 파란 선택 표면과 전경을 사용한다', () => {
    render(<ConversionStages status="processing" />)

    const current = screen.getByText('쉬운 글 변환').closest('li')
    const marker = current?.querySelector('[aria-hidden="true"]')
    expect(marker).toHaveClass('bg-selection', 'text-selection-foreground')
    expect(marker).not.toHaveClass('bg-accent', 'text-primary')
    expect(current).toHaveTextContent('진행 중')
  })
})
