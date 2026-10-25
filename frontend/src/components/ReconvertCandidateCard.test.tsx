import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { ReconvertCandidateCard } from './ReconvertCandidateCard'

describe('ReconvertCandidateCard', () => {
  it('재변환 후보는 브랜드 노란색 대신 파란 선택 표면을 사용한다', () => {
    render(
      <ReconvertCandidateCard
        sourceUnitIndex={0}
        candidateText="쉽게 다시 쓴 문장입니다."
        mode="replace"
        onReplace={vi.fn()}
        onInsert={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const card = screen.getByRole('region', { name: '원본 1번째 문단 재변환 후보' })
    expect(card).toHaveClass('bg-selection', 'text-selection-foreground')
    expect(card).not.toHaveClass('bg-accent')
    expect(screen.getByRole('status')).toHaveTextContent('재변환 후보가 도착했습니다.')
  })
})
