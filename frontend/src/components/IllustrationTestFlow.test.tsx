import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import type { IllustrationSuggestion } from '../api/types'
import { IllustrationTestFlow } from './IllustrationTestFlow'

const suggestion: IllustrationSuggestion = {
  suggestion_id: 's1',
  purpose: 'procedure',
  reason: '순서 안내',
  body_range: { start: 0, end: 0 },
  source_anchors: [{ source_unit_indexes: [0], quote: '신청서 제출' }],
  scenes: ['신청서 제출', '결과 확인'],
  preserved_facts: [],
  alt_text_draft: '신청하고 결과를 확인하는 순서',
}

describe('무료 그림 테스트 흐름', () => {
  it('명시적 생성·대체텍스트 확인 뒤에만 적용하고 제거할 수 있다', async () => {
    const user = userEvent.setup()
    render(
      <IllustrationTestFlow suggestion={suggestion} excerpt="신청서를 내세요." disabled={false} />,
    )
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText(/실제 AI가 그린 그림이 아닙니다/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '이 내용으로 그림 만들기' }))
    expect(screen.getByRole('status')).toHaveTextContent('만들고 있어요')
    expect(await screen.findByRole('img')).toHaveAccessibleName(suggestion.alt_text_draft)
    const apply = screen.getByRole('button', { name: '문서 미리보기에 추가' })
    expect(apply).toBeDisabled()
    await user.click(screen.getByRole('checkbox'))
    fireEvent.change(screen.getByLabelText('그림 대체텍스트'), { target: { value: '수정한 설명' } })
    expect(apply).toBeDisabled()
    await user.click(screen.getByRole('checkbox'))
    await user.click(apply)
    expect(screen.getByRole('heading', { name: '문서 적용 미리보기' })).toBeInTheDocument()
    expect(screen.getByText('신청서를 내세요.')).toBeInTheDocument()
    expect(screen.getByRole('img')).toHaveAccessibleName('수정한 설명')
    await user.click(screen.getByRole('button', { name: '문서 미리보기에서 제거' }))
    expect(screen.queryByRole('heading', { name: '문서 적용 미리보기' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '문서 미리보기에 추가' })).toBeDisabled()
  })

  it('본문 수정·충돌 중에는 그림을 숨기고 새 요청을 차단한다', async () => {
    const user = userEvent.setup()
    const view = render(
      <IllustrationTestFlow suggestion={suggestion} excerpt="본문" disabled={false} />,
    )
    await user.click(screen.getByRole('button', { name: '이 내용으로 그림 만들기' }))
    await screen.findByRole('img')
    view.rerender(<IllustrationTestFlow suggestion={suggestion} excerpt="본문 수정" disabled />)
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('본문을 저장하고 최신 제안')
  })
})
